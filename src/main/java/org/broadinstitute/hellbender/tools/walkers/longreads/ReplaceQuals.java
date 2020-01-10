package org.broadinstitute.hellbender.tools.walkers.longreads;

import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.engine.*;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.SAMFileGATKReadWriter;
import picard.cmdline.programgroups.ReadDataManipulationProgramGroup;

/**
 * Quickly count errors
 *
 * <h3>Input</h3>
 * <ul>
 *     <li>A single BAM file</li>
 * </ul>
 *
 * <h3>Outputs</h3>
 * <ul>
 *     <li>A collection of BAM files where care has been keep reads from the same ZMW in the same file</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <h4>Quickly count errors</h4>
 * <pre>
 *   gatk QuicklyCountMismatches \
 *     -I input.bam \
 *     -O output.txt
 * </pre>
 */
@DocumentedFeature
@CommandLineProgramProperties(
        summary = "Quickly count errors",
        oneLineSummary = "Quickly count errors",
        programGroup = ReadDataManipulationProgramGroup.class
)
public final class ReplaceQuals extends ReadWalker {
    @Argument(fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            doc="Write output to this file")
    public String output;
    private SAMFileGATKReadWriter outputWriter;

    @Override
    public void onTraversalStart() {
        outputWriter = createSAMWriter(IOUtils.getPath(output), true);
    }

    @Override
    public void apply(GATKRead read, ReferenceContext referenceContext, FeatureContext featureContext ) {
        byte[] quals = new byte[read.getBases().length];
        for (int i = 0; i < quals.length; i++) {
            quals[i] = 40;
        }
        read.setBaseQualities(quals);

        outputWriter.addRead(read);
    }

    @Override
    public void closeTool() {
        if ( outputWriter != null ) {
            outputWriter.close();
        }
    }
}