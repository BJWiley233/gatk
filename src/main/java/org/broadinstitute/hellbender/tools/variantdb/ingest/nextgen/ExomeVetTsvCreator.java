package org.broadinstitute.hellbender.tools.variantdb.ingest.nextgen;
import htsjdk.variant.variantcontext.VariantContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class ExomeVetTsvCreator extends VetTsvCreator {

    static final Logger logger = LogManager.getLogger(ExomeVetTsvCreator.class);

    public ExomeVetTsvCreator(String sampleName, String sampleId, Path sampleDirectoryPath) {
        super(sampleName, sampleId, sampleDirectoryPath);
    }

    @Override
    public List<String> createRow(final long start, final VariantContext variant, final String sampleId) {
        List<String> row = new ArrayList<>();
        row.add(String.valueOf(start));
        row.add(sampleId);
        for ( final ExomeFieldEnum fieldEnum : ExomeFieldEnum.values() ) {
            if (!fieldEnum.equals(ExomeFieldEnum.position) && !fieldEnum.equals(ExomeFieldEnum.sample)) {
                row.add(fieldEnum.getColumnValue(variant));
            }
        }
        return row;
    }

    public static List<String> getHeaders() {
        return Arrays.stream(ExomeFieldEnum.values()).map(String::valueOf).collect(Collectors.toList());
    }
}
