package org.broadinstitute.hellbender.tools.walkers.haplotypecaller;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.variant.variantcontext.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.engine.FeatureContext;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.engine.ReferenceDataSource;
import org.broadinstitute.hellbender.engine.ReferenceMemorySource;
import org.broadinstitute.hellbender.tools.haplotypecaller.GenotypePriorCalculator;
import org.broadinstitute.hellbender.tools.haplotypecaller.SimpleGenotypePriorCalculator;
import org.broadinstitute.hellbender.tools.walkers.annotator.VariantAnnotatorEngine;
import org.broadinstitute.hellbender.tools.walkers.genotyper.*;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.clipping.ReadClipper;
import org.broadinstitute.hellbender.utils.genotyper.*;
import org.broadinstitute.hellbender.utils.haplotype.EventMap;
import org.broadinstitute.hellbender.utils.haplotype.Haplotype;
import org.broadinstitute.hellbender.utils.pairhmm.DragstrReferenceSTRs;
import org.broadinstitute.hellbender.utils.pairhmm.DragstrUtils;
import org.broadinstitute.hellbender.utils.param.ParamUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.reference.ReferenceBases;
import org.broadinstitute.hellbender.utils.variant.GATKVariantContextUtils;

import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HaplotypeCaller's genotyping strategy implementation.
 */
public class HaplotypeCallerGenotypingEngine extends GenotypingEngine<StandardCallerArgumentCollection> {

    private static final Logger logger = LogManager.getLogger(HaplotypeCallerGenotypingEngine.class);


    private final GenotypersModel genotypingModel;

    private final PloidyModel ploidyModel;
    private final ReferenceConfidenceMode referenceConfidenceMode;
    protected final double snpHeterozygosity;
    protected final double indelHeterozygosity;

    private final int maxGenotypeCountToEnumerate;
    private final Map<Integer, Integer> practicalAlleleCountForPloidy = new HashMap<>();

    protected final boolean doPhysicalPhasing;

    private final HaplotypeCallerArgumentCollection hcArgs;

    // Debug stream managed by the HaplotypeCallerEngine
    private PrintStream genotyperDebugOutStream = null;

    /**
     * {@inheritDoc}
     * @param configuration {@inheritDoc}
     * @param samples {@inheritDoc}
     * @param doPhysicalPhasing whether to try physical phasing.
     */
    public HaplotypeCallerGenotypingEngine(final HaplotypeCallerArgumentCollection configuration, final SampleList samples, final boolean doPhysicalPhasing, final boolean applyBQD) {
        super(configuration.standardArgs, samples, false);
        hcArgs = configuration;
        this.doPhysicalPhasing = doPhysicalPhasing;
        ploidyModel = new HomogeneousPloidyModel(samples,configuration.standardArgs.genotypeArgs.samplePloidy);
        genotypingModel = hcArgs.applyBQD || hcArgs.applyFRD ?
                new DRAGENGenotypesModel(applyBQD, hcArgs.applyFRD, hcArgs.informativeReadOverlapMargin, hcArgs.maxEffectiveDepthAdjustment, hcArgs.likelihoodArgs.dragstrParams) :
                new IndependentSampleGenotypesModel();
        maxGenotypeCountToEnumerate = configuration.standardArgs.genotypeArgs.MAX_GENOTYPE_COUNT;
        referenceConfidenceMode = configuration.emitReferenceConfidence;
        snpHeterozygosity = configuration.standardArgs.genotypeArgs.snpHeterozygosity;
        indelHeterozygosity = configuration.standardArgs.genotypeArgs.indelHeterozygosity;
    }

    @Override
    protected String callSourceString() {
        return "HC_call";
    }

    @Override
    protected boolean forceKeepAllele(final Allele allele) {
        return allele.equals(Allele.NON_REF_ALLELE,false) || referenceConfidenceMode != ReferenceConfidenceMode.NONE;
    }



    /**
     * Main entry point of class - given a particular set of haplotypes, samples and reference context, compute
     * genotype likelihoods and assemble into a list of variant contexts and genomic events ready for calling
     *
     * The list of samples we're working with is obtained from the readLikelihoods
     *
     * @param haplotypes                             Haplotypes to assign likelihoods to
     * @param readLikelihoods                        Map from reads->(haplotypes,likelihoods)
     * @param perSampleFilteredReadList              Map from sample to reads that were filtered after assembly and before calculating per-read likelihoods.
     * @param ref                                    Reference bytes at active region
     * @param refLoc                                 Corresponding active region genome location
     * @param activeRegionWindow                     Active window
     * @param givenAlleles                Alleles to genotype
     * @param emitReferenceConfidence whether we should add a &lt;NON_REF&gt; alternative allele to the result variation contexts.
     * @param maxMnpDistance Phased substitutions separated by this distance or less are merged into MNPs.  More than
     *                       two substitutions occuring in the same alignment block (ie the same M/X/EQ CIGAR element)
     *                       are merged until a substitution is separated from the previous one by a greater distance.
     *                       That is, if maxMnpDistance = 1, substitutions at 10,11,12,14,15,17 are partitioned into a MNP
     *                       at 10-12, a MNP at 14-15, and a SNP at 17.  May not be negative.
     * @param withBamOut whether to annotate reads in readLikelihoods for future writing to bamout
     *
     * @return                                       A CalledHaplotypes object containing a list of VC's with genotyped events and called haplotypes
     *
     */
    public CalledHaplotypes assignGenotypeLikelihoods(final List<Haplotype> haplotypes,
                                                      final AlleleLikelihoods<GATKRead, Haplotype> readLikelihoods,
                                                      final Map<String, List<GATKRead>> perSampleFilteredReadList,
                                                      final byte[] ref,
                                                      final SimpleInterval refLoc,
                                                      final SimpleInterval activeRegionWindow,
                                                      final FeatureContext tracker,
                                                      final List<VariantContext> givenAlleles,
                                                      final boolean emitReferenceConfidence,
                                                      final int maxMnpDistance,
                                                      final SAMFileHeader header,
                                                      final boolean withBamOut) {
        // sanity check input arguments
        Utils.nonEmpty(haplotypes, "haplotypes input should be non-empty and non-null");
        Utils.validateArg(readLikelihoods != null && readLikelihoods.numberOfSamples() > 0, "readLikelihoods input should be non-empty and non-null");
        Utils.validateArg(ref != null && ref.length > 0, "ref bytes input should be non-empty and non-null");
        Utils.nonNull(refLoc, "refLoc must be non-null");
        Utils.validateArg(refLoc.size() == ref.length, " refLoc length must match ref bytes");
        Utils.nonNull(activeRegionWindow, "activeRegionWindow must be non-null");
        Utils.nonNull(givenAlleles, "givenAlleles must be non-null");
        Utils.validateArg(refLoc.contains(activeRegionWindow), "refLoc must contain activeRegionWindow");
        ParamUtils.isPositiveOrZero(maxMnpDistance, "maxMnpDistance may not be negative.");

        // update the haplotypes so we're ready to call, getting the ordered list of positions on the reference
        // that carry events among the haplotypes
        final SortedSet<Integer> startPosKeySet = EventMap.buildEventMapsForHaplotypes(haplotypes, ref, refLoc, hcArgs.assemblerArgs.debugAssembly, maxMnpDistance);

        // Walk along each position in the key set and create each event to be outputted
        final Set<Haplotype> calledHaplotypes = new HashSet<>();
        final List<VariantContext> returnCalls = new ArrayList<>();
        final int ploidy = configuration.genotypeArgs.samplePloidy;
        final List<Allele> noCallAlleles = GATKVariantContextUtils.noCallAlleles(ploidy);

        if (withBamOut) {
            //add annotations to reads for alignment regions and calling regions
            AssemblyBasedCallerUtils.annotateReadLikelihoodsWithRegions(readLikelihoods, activeRegionWindow);
        }

        final DragstrReferenceSTRs dragstrs = isDragstrSTRAnalyzerNecessary(startPosKeySet, haplotypes)
                ? DragstrReferenceSTRs.of(ref,startPosKeySet.first() - refLoc.getStart(),
                startPosKeySet.last() + 2 - refLoc.getStart(), hcArgs.likelihoodArgs.dragstrParams.maximumPeriod())
                : null;

        for( final int loc : startPosKeySet ) {
            if( loc < activeRegionWindow.getStart() || loc > activeRegionWindow.getEnd() ) {
                continue;
            }

            final List<VariantContext> eventsAtThisLoc = AssemblyBasedCallerUtils.getVariantContextsFromActiveHaplotypes(loc, haplotypes, !hcArgs.disableSpanningEventGenotyping);

            final List<VariantContext> eventsAtThisLocWithSpanDelsReplaced = replaceSpanDels(eventsAtThisLoc,
                    Allele.create(ref[loc - refLoc.getStart()], true), loc);

            VariantContext mergedVC = AssemblyBasedCallerUtils.makeMergedVariantContext(eventsAtThisLocWithSpanDelsReplaced);

            if( mergedVC == null ) {
                continue;
            }
            
            int mergedAllelesListSizeBeforePossibleTrimming = mergedVC.getAlleles().size();

            final Map<Allele, List<Haplotype>> alleleMapper = AssemblyBasedCallerUtils.createAlleleMapper(mergedVC, loc, haplotypes);

            if( hcArgs.assemblerArgs.debugAssembly && logger != null ) {
                logger.info("Genotyping event at " + loc + " with alleles = " + mergedVC.getAlleles());
            }

            //TODO this might need to be revisited given the extra genotyping code that will possibly exist in the future...
            mergedVC = removeAltAllelesIfTooManyGenotypes(ploidy, alleleMapper, mergedVC);

            AlleleLikelihoods<GATKRead, Allele> readAlleleLikelihoods = readLikelihoods.marginalize(alleleMapper);
            final SAMSequenceDictionary sequenceDictionary = header.getSequenceDictionary();
            final SimpleInterval variantCallingRelevantOverlap = new SimpleInterval(mergedVC).expandWithinContig(hcArgs.informativeReadOverlapMargin, sequenceDictionary);
            // We want to retian evidence that overlaps within its softclipping edges.
            if (hcArgs.applyBQD || hcArgs.applyFRD) { //TODO this is not hte right set of arguemnts
                readAlleleLikelihoods.retainEvidence(r -> ReadClipper.revertSoftClippedBases(r).overlaps(variantCallingRelevantOverlap));
            } else {
                readAlleleLikelihoods.retainEvidence(r -> r.overlaps(variantCallingRelevantOverlap));
            }
            readAlleleLikelihoods.setVariantCallingSubsetUsed(variantCallingRelevantOverlap);
            if (configuration.isSampleContaminationPresent()) {
                readAlleleLikelihoods.contaminationDownsampling(configuration.getSampleContamination());
            }
            if (genotyperDebugOutStream != null) {
                genotyperDebugOutStream.println("\n=============================================================================");
                genotyperDebugOutStream.println("Event at: " + mergedVC + " with " + readAlleleLikelihoods.evidenceCount() + " reads and "+readAlleleLikelihoods.filteredSampleEvidence(0).size()+" disqualified");
                genotyperDebugOutStream.println("=============================================================================");
            }

            if (emitReferenceConfidence) {
                mergedVC = ReferenceConfidenceUtils.addNonRefSymbolicAllele(mergedVC);
                readAlleleLikelihoods.addNonReferenceAllele(Allele.NON_REF_ALLELE);
                mergedAllelesListSizeBeforePossibleTrimming++;
            }
            final GenotypesContext genotypes = calculateGLsForThisEvent(readAlleleLikelihoods, mergedVC, noCallAlleles, ref, loc - refLoc.getStart(), dragstrs);
            final GenotypePriorCalculator gpc = resolveCustomAlleleFrequencyCalculator(mergedVC, dragstrs, loc - refLoc.getStart() + 1, ploidy, snpHeterozygosity, indelHeterozygosity);
            final VariantContext call = calculateGenotypes(new VariantContextBuilder(mergedVC).genotypes(genotypes).make(), gpc, givenAlleles);
            if( call != null ) {
                readAlleleLikelihoods = prepareReadAlleleLikelihoodsForAnnotation(readLikelihoods, perSampleFilteredReadList,
                        emitReferenceConfidence, alleleMapper, readAlleleLikelihoods, call, variantCallingRelevantOverlap);

                VariantContext annotatedCall = makeAnnotatedCall(ref, refLoc, tracker, header, mergedVC, mergedAllelesListSizeBeforePossibleTrimming, readAlleleLikelihoods, call, annotationEngine);

                if (dragstrs != null && GATKVariantContextUtils.containsInlineIndel(annotatedCall)) {
                    final int strOffset = loc - refLoc.getStart() + 1;
                    annotatedCall = DragstrUtils.annotate(annotatedCall, hcArgs.likelihoodArgs.dragstrParams, dragstrs.period(strOffset), dragstrs.repeatLength(strOffset));
                }
                returnCalls.add( annotatedCall );

                if (withBamOut) {
                    AssemblyBasedCallerUtils.annotateReadLikelihoodsWithSupportedAlleles(call, readAlleleLikelihoods);
                }
                // maintain the set of all called haplotypes
                call.getAlleles().stream().map(alleleMapper::get).filter(Objects::nonNull).forEach(calledHaplotypes::addAll);
            }
        }

        final List<VariantContext> phasedCalls = doPhysicalPhasing ? AssemblyBasedCallerUtils.phaseCalls(returnCalls, calledHaplotypes) : returnCalls;
        return new CalledHaplotypes(phasedCalls, calledHaplotypes);
    }

    /**
     * Confirms wether there is the need to analyze the region's reference sequence for the presence of STRs.
     * <p>
     *     This is only the case when DRAGstr is activate, we are going to use their priors and there is some indel
     *     amongst the haplotypes.
     * </p>
     */
    private boolean isDragstrSTRAnalyzerNecessary(SortedSet<Integer> startPosKeySet, List<Haplotype> haplotypes) {
        if (startPosKeySet.isEmpty()) {
            return false;
        } else if (hcArgs.likelihoodArgs.dragstrParams == null) {
            return false;
        } else if (hcArgs.standardArgs.genotypeArgs.dontUseDragstrPriors) {
            return false;
        } else {
            for (final Haplotype haplotype : haplotypes) {
                for (final VariantContext vc : haplotype.getEventMap().getVariantContexts()) {
                    if (GATKVariantContextUtils.containsInlineIndel(vc)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private GenotypePriorCalculator resolveCustomAlleleFrequencyCalculator(final VariantContext vc, final DragstrReferenceSTRs strs, final int pos, final int ploidy, final double snpHeterozygosity, final double indelHeterozygosity) {
       if (hcArgs.likelihoodArgs.dragstrParams == null || hcArgs.standardArgs.genotypeArgs.dontUseDragstrPriors) {
            return SimpleGenotypePriorCalculator.assumingHW(Math.log10(snpHeterozygosity), Math.log10(indelHeterozygosity));
        } else if (strs == null) {
            return SimpleGenotypePriorCalculator.givenHetToHomRatio(Math.log10(snpHeterozygosity), Math.log10(indelHeterozygosity), Math.log10(Math.min(snpHeterozygosity, indelHeterozygosity)), hcArgs.likelihoodArgs.dragstrHetHomRatio);
       } else {
            final int period = strs.period(pos);
            final int repeats = strs.repeatLength(pos);
            return SimpleGenotypePriorCalculator.givenDragstrParams(hcArgs.likelihoodArgs.dragstrParams, period, repeats, Math.log10(snpHeterozygosity), hcArgs.likelihoodArgs.dragstrHetHomRatio);
        }
    }

    @VisibleForTesting
    static List<VariantContext> replaceSpanDels(final List<VariantContext> eventsAtThisLoc, final Allele refAllele, final int loc) {
        return eventsAtThisLoc.stream().map(vc -> replaceWithSpanDelVC(vc, refAllele, loc)).collect(Collectors.toList());
    }

    @VisibleForTesting
    static VariantContext replaceWithSpanDelVC(final VariantContext variantContext, final Allele refAllele, final int loc) {
        if (variantContext.getStart() == loc) {
            return variantContext;
        } else {
            VariantContextBuilder builder = new VariantContextBuilder(variantContext)
                    .start(loc)
                    .stop(loc)
                    .alleles(Arrays.asList(refAllele, Allele.SPAN_DEL))
                    .genotypes(GenotypesContext.NO_GENOTYPES);
            return builder.make();
        }

    }

    /**
     * If the number of alleles is so high that enumerating all possible genotypes is impractical, as determined by
     * {@link #maxGenotypeCountToEnumerate}, remove alt alleles from the input {@code alleleMapper} that are
     * not well supported by good-scored haplotypes.
     * Otherwise do nothing.
     *
     * Alleles kept are guaranteed to have higher precedence than those removed, where precedence is determined by
     * {@link AlleleScoredByHaplotypeScores}.
     *
     * After the remove operation, entries in map are guaranteed to have the same relative order as they were in the input map,
     * that is, entries will be only be removed but not not shifted relative to each other.
     *  @param ploidy        ploidy of the sample
     * @param alleleMapper  original allele to haplotype map
     */
    private VariantContext removeAltAllelesIfTooManyGenotypes(final int ploidy, final Map<Allele, List<Haplotype>> alleleMapper, final VariantContext mergedVC) {

        final int originalAlleleCount = alleleMapper.size();
        practicalAlleleCountForPloidy.putIfAbsent(ploidy, GenotypeLikelihoodCalculators.computeMaxAcceptableAlleleCount(ploidy, maxGenotypeCountToEnumerate));
        final int practicalAlleleCount = practicalAlleleCountForPloidy.get(ploidy);

        if (originalAlleleCount > practicalAlleleCount) {
            final List<Allele> allelesToKeep = whichAllelesToKeepBasedonHapScores(alleleMapper, practicalAlleleCount);
            alleleMapper.keySet().retainAll(allelesToKeep);
            logger.warn(String.format("At position %s removed alt alleles where ploidy is %d and original allele count is %d, whereas after trimming the allele count becomes %d. Alleles kept are:%s",
                    new SimpleInterval(mergedVC).toString(), ploidy, originalAlleleCount, practicalAlleleCount, allelesToKeep));
            return removeExcessAltAllelesFromVC(mergedVC, allelesToKeep);
        } else {
            return mergedVC;
        }
    }

    /**
     * Returns a list of alleles that is a subset of the key set of input map {@code alleleMapper}.
     * The size of the returned list is min({@code desiredNumOfAlleles}, alleleMapper.size()).
     *
     * Alleles kept are guaranteed to have higher precedence than those removed, where precedence is determined by
     * {@link AlleleScoredByHaplotypeScores}.
     *
     * Entries in the returned list are guaranteed to have the same relative order as they were in the input map.
     *
     * @param alleleMapper          original allele to haplotype map
     * @param desiredNumOfAlleles   desired allele count, including ref allele
     */
    @VisibleForTesting
    static List<Allele> whichAllelesToKeepBasedonHapScores(final Map<Allele, List<Haplotype>> alleleMapper,
                                                           final int desiredNumOfAlleles) {

        if(alleleMapper.size() <= desiredNumOfAlleles){
            return alleleMapper.keySet().stream().collect(Collectors.toList());
        }

        final PriorityQueue<AlleleScoredByHaplotypeScores> alleleMaxPriorityQ = new PriorityQueue<>();
        for(final Allele allele : alleleMapper.keySet()){
            final List<Double> hapScores = alleleMapper.get(allele).stream().map(Haplotype::getScore).sorted().collect(Collectors.toList());
            final Double highestScore = hapScores.size() > 0 ? hapScores.get(hapScores.size()-1) : Double.NEGATIVE_INFINITY;
            final Double secondHighestScore = hapScores.size()>1 ? hapScores.get(hapScores.size()-2) : Double.NEGATIVE_INFINITY;

            alleleMaxPriorityQ.add(new AlleleScoredByHaplotypeScores(allele, highestScore, secondHighestScore));
        }

        final Set<Allele> allelesToRetain = new LinkedHashSet<>();
        while(allelesToRetain.size()<desiredNumOfAlleles){
            allelesToRetain.add(alleleMaxPriorityQ.poll().getAllele());
        }
        return alleleMapper.keySet().stream().filter(allelesToRetain::contains).collect(Collectors.toList());
    }

    // Add the debug output stream managed by the engine, this should be called as part of initialization
    void addDebugOutStream(final PrintStream genotyperDebugOutStream) {
        this.genotyperDebugOutStream = genotyperDebugOutStream;
        this.genotypingModel.addDebugOutStream(genotyperDebugOutStream);
    }

    /**
     * A utility class that provides ordering information, given best and second best haplotype scores.
     * If there's a tie between the two alleles when comparing their best haplotype score, the second best haplotype score
     * is used for breaking the tie. In the case that one allele doesn't have a second best allele, i.e. it has only one
     * supportive haplotype, its second best score is set as {@link Double#NEGATIVE_INFINITY}.
     * In the extremely unlikely cases that two alleles, having the same best haplotype score, neither have a second
     * best haplotype score, or the same second best haplotype score, the order is exactly the same as determined by
     * {@link Allele#compareTo(Allele)}.
     */
    private static final class AlleleScoredByHaplotypeScores implements Comparable<AlleleScoredByHaplotypeScores>{
        private final Allele allele;
        private final Double bestHaplotypeScore;
        private final Double secondBestHaplotypeScore;

        public AlleleScoredByHaplotypeScores(final Allele allele, final Double bestHaplotypeScore, final Double secondBestHaplotypeScore){
            this.allele = allele;
            this.bestHaplotypeScore = bestHaplotypeScore;
            this.secondBestHaplotypeScore = secondBestHaplotypeScore;
        }

        @Override
        public int compareTo(final AlleleScoredByHaplotypeScores other) {

            if(allele.isReference() && other.allele.isNonReference()){
                return -1;
            } else if(allele.isNonReference() && other.allele.isReference()){
                return 1;
            } else if(bestHaplotypeScore > other.bestHaplotypeScore) {
                return -1;
            } else if (bestHaplotypeScore < other.bestHaplotypeScore) {
                return 1;
            } else if (!secondBestHaplotypeScore.equals(other.secondBestHaplotypeScore)) {
                return secondBestHaplotypeScore > other.secondBestHaplotypeScore ? -1 : 1;
            } else {
                return allele.compareTo(other.allele);
            }
        }

        public Allele getAllele(){
            return allele;
        }
    }

    /**
     * Returns an VC that is similar to {@code inputVC} in every aspect except that alleles not in {@code allelesToKeep}
     * are removed in the returned VC.
     * @throws IllegalArgumentException if 1) {@code allelesToKeep} is null or contains null elements; or
     *                                     2) {@code allelesToKeep} doesn't contain a reference allele; or
     *                                     3) {@code allelesToKeep} is not a subset of {@code inputVC.getAlleles()}
     */
    @VisibleForTesting
    static VariantContext removeExcessAltAllelesFromVC(final VariantContext inputVC, final Collection<Allele> allelesToKeep){
        Utils.validateArg(allelesToKeep!=null, "alleles to keep is null");
        Utils.validateArg(!allelesToKeep.contains(null), "alleles to keep contains null elements");
        Utils.validateArg(allelesToKeep.stream().anyMatch(Allele::isReference), "alleles to keep doesn't contain reference allele!");
        Utils.validateArg(inputVC.getAlleles().containsAll(allelesToKeep), "alleles to keep is not a subset of input VC alleles");
        if(inputVC.getAlleles().size() == allelesToKeep.size()) return inputVC;

        final VariantContextBuilder vcb = new VariantContextBuilder(inputVC);
        final List<Allele> originalList = inputVC.getAlleles();
        originalList.retainAll(allelesToKeep);
        vcb.alleles(originalList);
        return vcb.make();
    }

    @VisibleForTesting
    static protected VariantContext makeAnnotatedCall(byte[] ref, SimpleInterval refLoc, FeatureContext tracker, SAMFileHeader header, VariantContext mergedVC, int mergedAllelesListSizeBeforePossibleTrimming, AlleleLikelihoods<GATKRead, Allele> readAlleleLikelihoods, VariantContext call, VariantAnnotatorEngine annotationEngine) {
        final SimpleInterval locus = new SimpleInterval(mergedVC);
        final SAMSequenceDictionary sequenceDictionary = header.getSequenceDictionary();
        final SimpleInterval refLocInterval= new SimpleInterval(refLoc);
        final ReferenceDataSource refData = new ReferenceMemorySource(new ReferenceBases(ref, refLocInterval), sequenceDictionary);
        final ReferenceContext referenceContext = new ReferenceContext(refData, locus, refLocInterval);

        final VariantContext untrimmedResult =  annotationEngine.annotateContext(call, tracker, referenceContext, readAlleleLikelihoods, a -> true);



        // NOTE: We choose to reverseTrimAlleles() here as opposed to when we actually do the trimming because otherwise we would have to resolve
        //       the mismatching readAlleleLikelihoods object which is keyed to the old, possibly incorrectly trimmed alleles.
        return untrimmedResult.getAlleles().size() == mergedAllelesListSizeBeforePossibleTrimming ? untrimmedResult
                : GATKVariantContextUtils.reverseTrimAlleles(untrimmedResult);
    }

    /**
     * For a particular event described in inputVC, form PL vector for each sample by looking into allele read map and filling likelihood matrix for each allele
     * @param readLikelihoods          Allele map describing mapping from reads to alleles and corresponding likelihoods
     * @param mergedVC               Input VC with event to genotype
     * @return                       GenotypesContext object wrapping genotype objects with PLs
     */
    protected GenotypesContext calculateGLsForThisEvent(final AlleleLikelihoods<GATKRead, Allele> readLikelihoods, final VariantContext mergedVC, final List<Allele> noCallAlleles, final byte[] paddedReference, final int offsetForRefIntoEvent, final DragstrReferenceSTRs dragstrs) {
        Utils.nonNull(readLikelihoods, "readLikelihoods");
        Utils.nonNull(mergedVC, "mergedVC");
        final List<Allele> vcAlleles = mergedVC.getAlleles();
        final AlleleList<Allele> alleleList = readLikelihoods.numberOfAlleles() == vcAlleles.size() ? readLikelihoods : new IndexedAlleleList<>(vcAlleles);
        final GenotypingLikelihoods<Allele> likelihoods = genotypingModel.calculateLikelihoods(alleleList,new GenotypingData<>(ploidyModel,readLikelihoods), paddedReference, offsetForRefIntoEvent, dragstrs);
        final int sampleCount = samples.numberOfSamples();
        final GenotypesContext result = GenotypesContext.create(sampleCount);
        for (int s = 0; s < sampleCount; s++) {
            result.add(new GenotypeBuilder(samples.getSample(s)).alleles(noCallAlleles).PL(likelihoods.sampleLikelihoods(s).getAsPLs()).make());
        }
        return result;
    }

    /**
     * Returns the ploidy-model used by this genotyping engine.
     *
     * @return never {@code null}.
     */
    public PloidyModel getPloidyModel() {
        return ploidyModel;
    }

    // Builds the read-likelihoods collection to use for annotation considering user arguments and the collection
    // used for genotyping.
    private AlleleLikelihoods<GATKRead, Allele> prepareReadAlleleLikelihoodsForAnnotation(
            final AlleleLikelihoods<GATKRead, Haplotype> readHaplotypeLikelihoods,
            final Map<String, List<GATKRead>> perSampleFilteredReadList,
            final boolean emitReferenceConfidence,
            final Map<Allele, List<Haplotype>> alleleMapper,
            final AlleleLikelihoods<GATKRead, Allele> readAlleleLikelihoodsForGenotyping,
            final VariantContext call,
            final SimpleInterval relevantReadsOverlap) {

        final AlleleLikelihoods<GATKRead, Allele> readAlleleLikelihoodsForAnnotations;

        // We can reuse for annotation the likelihood for genotyping as long as there is no contamination filtering
        // or the user want to use the contamination filtered set for annotations.
        // Otherwise (else part) we need to do it again.
        if (hcArgs.useFilteredReadMapForAnnotations || !configuration.isSampleContaminationPresent()) {
            readAlleleLikelihoodsForAnnotations = readAlleleLikelihoodsForGenotyping;
            // the input likelihoods are supposed to have been filtered to only overlapping reads so no need to
            // do it again.
        } else {
            readAlleleLikelihoodsForAnnotations = readHaplotypeLikelihoods.marginalize(alleleMapper);
            readAlleleLikelihoodsForAnnotations.retainEvidence(relevantReadsOverlap::overlaps);
            if (emitReferenceConfidence) {
                readAlleleLikelihoodsForAnnotations.addNonReferenceAllele(Allele.NON_REF_ALLELE);
            }
        }

        if (call.getAlleles().size() != readAlleleLikelihoodsForAnnotations.numberOfAlleles()) {
            readAlleleLikelihoodsForAnnotations.updateNonRefAlleleLikelihoods(new IndexedAlleleList<>(call.getAlleles()));
        }

        // Skim the filtered map based on the location so that we do not add filtered read that are going to be removed
        // right after a few lines of code below.
        final Map<String, List<GATKRead>> overlappingFilteredReads = overlappingFilteredReads(perSampleFilteredReadList, relevantReadsOverlap);

        readAlleleLikelihoodsForAnnotations.addEvidence(overlappingFilteredReads,0);

        return readAlleleLikelihoodsForAnnotations;
    }

    private static Map<String, List<GATKRead>> overlappingFilteredReads(final Map<String, List<GATKRead>> perSampleFilteredReadList, final SimpleInterval loc) {
        final Map<String,List<GATKRead>> overlappingFilteredReads = new HashMap<>(perSampleFilteredReadList.size());

        for (final Map.Entry<String,List<GATKRead>> sampleEntry : perSampleFilteredReadList.entrySet()) {
            final List<GATKRead> originalList = sampleEntry.getValue();
            final String sample = sampleEntry.getKey();
            if (originalList == null || originalList.isEmpty()) {
                continue;
            }
            final List<GATKRead> newList = originalList.stream()
                    .filter(read -> read.overlaps(loc))
                    .collect(Collectors.toCollection(() -> new ArrayList<>(originalList.size())));

            if (!newList.isEmpty()) {
                overlappingFilteredReads.put(sample,newList);
            }
        }
        return overlappingFilteredReads;
    }
}
