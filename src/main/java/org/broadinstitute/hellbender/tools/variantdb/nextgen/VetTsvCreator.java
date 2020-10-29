package org.broadinstitute.hellbender.tools.variantdb.nextgen;

import htsjdk.variant.variantcontext.VariantContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.engine.FeatureContext;
import org.broadinstitute.hellbender.engine.ReadsContext;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.variantdb.SchemaUtils;
import org.broadinstitute.hellbender.tools.variantdb.IngestConstants;
import org.broadinstitute.hellbender.utils.tsv.SimpleXSVWriter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class VetTsvCreator {

    static final Logger logger = LogManager.getLogger(VetTsvCreator.class);

    private SimpleXSVWriter vetWriter = null;
    private final String sampleId;
    private static String VET_FILETYPE_PREFIX = "vet_";


    public VetTsvCreator(String sampleName, String sampleId, String tableNumberPrefix, final File outputDirectory) {
        this.sampleId = sampleId;
        // If the vet directory inside it doesn't exist yet -- create it
        // if the pet & vet tsvs don't exist yet -- create them
        try {
            final File vetOutputFile = new File(outputDirectory, VET_FILETYPE_PREFIX + tableNumberPrefix + sampleName  + IngestConstants.FILETYPE);
            vetWriter = new SimpleXSVWriter(vetOutputFile.toPath(), IngestConstants.SEPARATOR);
            vetWriter.setHeaderLine(getHeaders());
        } catch (final IOException e) {
            throw new UserException("Could not create vet outputs", e);
        }
    }

    public void apply(VariantContext variant, ReadsContext readsContext, ReferenceContext referenceContext, FeatureContext featureContext) {
        int start = variant.getStart();
        List<String> row = createRow(
                SchemaUtils.encodeLocation(variant.getContig(), start),
                variant,
                sampleId
        );

        // write the variant to the XSV
        SimpleXSVWriter.LineBuilder vetLine = vetWriter.getNewLineBuilder();
        vetLine.setRow(row);
        vetLine.write();

    }

    public List<String> createRow(final long location, final VariantContext variant, final String sampleId) {
        List<String> row = new ArrayList<>();
        for ( final VetFieldEnum fieldEnum : VetFieldEnum.values() ) {
            if (fieldEnum.equals(VetFieldEnum.location)) {
                row.add(String.valueOf(location));
            } else if (fieldEnum.equals(VetFieldEnum.sample_id)) {
                row.add(sampleId);
            } else {
                row.add(fieldEnum.getColumnValue(variant));
            }
        }
        return row;
    }

    public static List<String> getHeaders() {
        return Arrays.stream(VetFieldEnum.values()).map(String::valueOf).collect(Collectors.toList());
    }

    public void closeTool() {
        if (vetWriter != null) {
            try {
                vetWriter.close();
            } catch (final Exception e) {
                throw new IllegalArgumentException("Couldn't close VET writer", e);
            }
        }

    }
}
