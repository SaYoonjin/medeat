package com.medeat.medical.service;

import java.util.List;
import java.util.Set;

import com.medeat.medical.dto.DrugInfoDto;
import com.medeat.medical.dto.DrugInfoSection;

public interface DrugInfoService {

    List<DrugInfoDto> searchDrug(String keyword) throws Exception;
    DrugInfoDto fetchDetailByItemSeq(String itemSeq);
    List<DrugInfoDto> searchDrugHybrid(String keyword) throws Exception;

    DrugInfoDto getDrugInfo(String itemSeq) throws Exception;
    DrugInfoDto getDrugInfo(Long itemSeq, String nameHint) throws Exception;
    DrugInfoDto getDrugInfoCached(
            Long itemSeq,
            String nameHint,
            Set<DrugInfoSection> requiredSections
    ) throws Exception;

    
 // ✅ PDF / MEDI 분석용
    List<DrugInfoDto> getDrugInfoListByItemSeq(List<Long> itemSeqList);
}
