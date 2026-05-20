package eu.griddigit.cimpal.main.application.CGMESConverter.requests;

import eu.griddigit.cimpal.main.application.tasks.CgmesAddSwitchingDevices;

import java.nio.file.Path;
import java.util.List;

public record AddSwitchingDevicesRequest(
        List<Path> modelInputFiles,
        Path mappingFile,
        CgmesAddSwitchingDevices.DataExchangeStandard standard,
        boolean importMappingFile,
        boolean applyLines,
        boolean applyPowerTransformer,
        boolean applySynchronousMachine,
        boolean exportMappingFile,
        boolean onlyForEquipmentInMappingFile,
        boolean saveResult
) {
    public String cgmesVersionString() {
        return switch (standard) {
            case CGMES_2_4 -> "CGMESv2.4";
            case CGMES_3_0 -> "CGMESv3.0";
        };
    }
}