package com.medeat.medical.controller;

import com.medeat.medical.dto.DrugInfoDto;
import com.medeat.medical.service.DrugInfoService;
import com.medeat.mediscan.service.MediScanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drug")
public class DrugInfoRestController {

    private final DrugInfoService drugInfoService;
    private final MediScanService mediScanService;

    public DrugInfoRestController(
            DrugInfoService drugInfoService,
            MediScanService mediScanService
    ) {
        this.drugInfoService = drugInfoService;
        this.mediScanService = mediScanService;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String keyword) throws Exception {
        return ResponseEntity.ok(drugInfoService.searchDrug(keyword));
    }

    @GetMapping("/detail/{itemSeq}")
    public ResponseEntity<DrugInfoDto> getDrugDetail(@PathVariable String itemSeq) throws Exception {
        return ResponseEntity.ok(drugInfoService.getDrugInfo(itemSeq));
    }
}
