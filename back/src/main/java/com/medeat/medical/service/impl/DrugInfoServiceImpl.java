package com.medeat.medical.service.impl;

import com.medeat.medical.dao.DrugInfoDao;
import com.medeat.medical.dto.DrugInfoDto;
import com.medeat.medical.dto.DrugInfoSection;
import com.medeat.medical.service.DrugInfoService;
import com.medeat.util.PillNameCsvLoader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DrugInfoServiceImpl implements DrugInfoService {

    private static final String SERVICE_KEY = "95b090c7ac91aae68ecf99841e8331489b4d7a0577c2993eb2f3f9afccdaf3eb";

    private final DrugInfoDao drugInfoDao;
    private final PillNameCsvLoader pillNameCsvLoader;

    @Value("${mfds.drug.cache-ttl-days:30}")
    private long cacheTtlDays;

    public DrugInfoServiceImpl(DrugInfoDao drugInfoDao, PillNameCsvLoader pillNameCsvLoader) {
        this.drugInfoDao = drugInfoDao;
        this.pillNameCsvLoader = pillNameCsvLoader;
    }

    /* =========================================================
       1) 이름 검색 (EasyDrug)
    ========================================================= */
    @Override
    public List<DrugInfoDto> searchDrug(String keyword) throws Exception {
        String url =
            "https://apis.data.go.kr/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList"
            + "?serviceKey=" + SERVICE_KEY
            + "&itemName=" + URLEncoder.encode(keyword, "UTF-8")
            + "&type=json";

        JSONObject body = callApi(url);
        if (body == null) return Collections.emptyList();

        JSONArray items = extractItems(body);
        if (items == null) return Collections.emptyList();

        List<DrugInfoDto> list = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            DrugInfoDto info = parseDrugInfo(items.getJSONObject(i));
            if (info.getItemSeq() != null) {
                drugInfoDao.upsertDrugInfo(info); // 캐시
            }
            list.add(info);
        }
        return list;
    }
    
    /* =========================================================
	    ✅ 1) 이름 검색 (Hybrid)
	    - 낱알식별(item_name)로 "외형/각인/이미지/색" 확보
	    - EasyDrug(itemName)로 "효능/용법/주의/상호작용/부작용/보관" 확보
	    - itemSeq 기준으로 merge
	 ========================================================= */
	 @Override
	 public List<DrugInfoDto> searchDrugHybrid(String keyword) throws Exception {
	     String k = (keyword == null) ? "" : keyword.trim();
	     if (k.isEmpty()) return Collections.emptyList();
	
	     // 1) 낱알식별(외형) 먼저: 리스트가 더 풍부한 경우가 많음
	     List<DrugInfoDto> pillList = fetchPillIdentifyByName(k, 30);
	
	     // 2) EasyDrug: 텍스트 정보(효능/용법 등)
	     List<DrugInfoDto> easyList = searchDrug(k); // 기존 메서드 재사용 (캐시 upsert 포함)
	
	     // 3) itemSeq 기준 merge
	     //    - pillList를 먼저 넣고, easyList로 "비어있는 필드" 채우기
	     LinkedHashMap<Long, DrugInfoDto> map = new LinkedHashMap<>();
	
	     for (DrugInfoDto p : pillList) {
	         if (p == null) continue;
	         Long seq = p.getItemSeq();
	         if (seq == null) continue;
	         map.put(seq, p);
	     }
	
	     for (DrugInfoDto e : easyList) {
	         if (e == null) continue;
	         Long seq = e.getItemSeq();
	         if (seq == null) continue;
	
	         if (!map.containsKey(seq)) {
	             map.put(seq, e);
	         } else {
	             DrugInfoDto merged = merge(map.get(seq), e); // 기존 merge() 사용
	             map.put(seq, merged);
	         }
	     }
	
	     // 4) 낱알식별에만 있고 EasyDrug에 없는 애들도 있으니,
	     //    list 반환 전에 DB 캐시 upsert (seq 있는 애만)
	     for (DrugInfoDto d : map.values()) {
	         if (d != null && d.getItemSeq() != null) {
	             drugInfoDao.upsertDrugInfo(d);
	         }
	     }
	
	     // 5) 결과가 너무 적으면, 정규화한 이름으로 한 번 더(선택)
	     if (map.isEmpty()) {
	         String nk = normalizeName(k);
	         if (!nk.equals(k)) {
	             List<DrugInfoDto> pill2 = fetchPillIdentifyByName(nk, 30);
	             for (DrugInfoDto p : pill2) {
	                 if (p == null || p.getItemSeq() == null) continue;
	                 map.putIfAbsent(p.getItemSeq(), p);
	             }
	             List<DrugInfoDto> easy2 = searchDrug(nk);
	             for (DrugInfoDto e : easy2) {
	                 if (e == null || e.getItemSeq() == null) continue;
	                 if (!map.containsKey(e.getItemSeq())) map.put(e.getItemSeq(), e);
	                 else map.put(e.getItemSeq(), merge(map.get(e.getItemSeq()), e));
	             }
	         }
	     }
	
	     return new ArrayList<>(map.values());
	 }
	
	 /* =========================================================
	    ✅ 낱알식별 API를 "이름(item_name)"으로 조회
	    - 문서상 파라미터: item_name (json 가능)  :contentReference[oaicite:1]{index=1}
	 ========================================================= */
	 private List<DrugInfoDto> fetchPillIdentifyByName(String keyword, int rows) {
	     try {
	         if (keyword == null) return Collections.emptyList();
	         String k = keyword.trim();
	         if (k.length() < 2) return Collections.emptyList();
	
	         int num = Math.max(1, Math.min(rows, 100));
	
	         String url =
	             "https://apis.data.go.kr/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03"
	             + "?serviceKey=" + SERVICE_KEY
	             + "&item_name=" + URLEncoder.encode(k, "UTF-8")
	             + "&pageNo=1&numOfRows=" + num
	             + "&type=json";
	
	         JSONObject body = callApi(url);
	         if (body == null) return Collections.emptyList();
	
	         JSONArray items = extractItems(body);
	         if (items == null || items.isEmpty()) return Collections.emptyList();
	
	         List<DrugInfoDto> list = new ArrayList<>();
	         for (int i = 0; i < items.length(); i++) {
	             DrugInfoDto info = parseDrugInfo(items.getJSONObject(i));
	             // 낱알식별은 itemSeq가 거의 항상 있음
	             list.add(info);
	         }
	         return list;
	
	     } catch (Exception e) {
	         return Collections.emptyList();
	     }
	 }


	 @Override
	 public DrugInfoDto getDrugInfo(String itemSeq) throws Exception {
	     Long seq = parseLong(itemSeq);
	     return getDrugInfo(seq, null);
	 }


    /* =========================================================
       2) 낱알식별(외형) API
    ========================================================= */
    private DrugInfoDto fetchDrugPillInfo(Long itemSeq) {
        try {
            String url =
                "https://apis.data.go.kr/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03"
                + "?serviceKey=" + SERVICE_KEY
                + "&item_seq=" + itemSeq
                + "&pageNo=1&numOfRows=1&type=json";

            JSONObject body = callApi(url);
            if (body == null) return null;

            JSONArray items = extractItems(body);
            if (items == null || items.isEmpty()) return null;

            return parseDrugInfo(items.getJSONObject(0));
        } catch (Exception e) {
            return null;
        }
    }

    /* =========================================================
       3) EasyDrug를 "이름"으로 조회
    ========================================================= */
    private DrugInfoDto fetchEasyDrugByName(String rawName) {
        try {
            if (rawName == null || rawName.isBlank()) return null;

            LinkedHashSet<String> keys = new LinkedHashSet<>();

            String k0 = rawName.trim();
            keys.add(k0);

            String k1 = removeParen(k0);
            keys.add(k1);

            String k2 = stripStrength(k1);
            keys.add(k2);

            String k3 = stripDosageForm(k2);
            keys.add(k3);

            for (String key : keys) {
                DrugInfoDto hit = fetchEasyDrugPickBest(key, 10);
                if (hit != null && hasText(hit)) return hit;
            }

            for (String key : keys) {
                DrugInfoDto hit = fetchEasyDrugPickBest(key, 10);
                if (hit != null) return hit;
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private DrugInfoDto fetchEasyDrugPickBest(String keyword, int rows) {
        try {
            if (keyword == null) return null;
            String k = keyword.trim();
            if (k.length() < 2) return null;

            String url =
                "https://apis.data.go.kr/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList"
                + "?serviceKey=" + SERVICE_KEY
                + "&itemName=" + URLEncoder.encode(k, "UTF-8")
                + "&pageNo=1&numOfRows=" + rows
                + "&type=json";

            JSONObject body = callApi(url);
            if (body == null) return null;

            JSONArray items = extractItems(body);
            if (items == null || items.isEmpty()) return null;

            DrugInfoDto bestAny = null;

            for (int i = 0; i < items.length(); i++) {
                DrugInfoDto d = parseDrugInfo(items.getJSONObject(i));
                if (bestAny == null) bestAny = d;

                if (hasText(d)) return d;
            }

            return bestAny;
        } catch (Exception e) {
            return null;
        }
    }

    // ----- normalize helpers -----

    private String removeParen(String s) {
        if (s == null) return null;
        return s.replaceAll("\\(.*?\\)", "").trim();
    }

    private String stripStrength(String s) {
        if (s == null) return null;
        String n = s;

        n = n.replaceAll("\\b\\d+(\\.\\d+)?\\s*(mg|g|mcg|μg|ug|IU|iu)\\b", "");
        n = n.replaceAll("\\d+(\\.\\d+)?\\s*(밀리그램|그램)\\b", "");
        n = n.replaceAll("\\s+", " ").trim();
        return n;
    }

    private String stripDosageForm(String s) {
        if (s == null) return null;
        String n = s;

        n = n.replaceAll("(캡슐|정|서방정|연질캡슐|시럽|현탁액|과립|연고)\\b", "");
        n = n.replaceAll("\\s+", " ").trim();
        return n;
    }

    /* =========================================================
       4) 핵심 getDrugInfo + nedrug fallback
    ========================================================= */
    @Override
    public DrugInfoDto getDrugInfoCached(
            Long itemSeq,
            String nameHint,
            Set<DrugInfoSection> requiredSections
    ) throws Exception {
        if (itemSeq == null) {
            return null;
        }

        DrugInfoDto cached = drugInfoDao.selectByItemSeq(itemSeq);
        if (isFresh(cached) && containsRequiredSections(cached, requiredSections)) {
            return cached;
        }

        DrugInfoDto target = cached;
        if (target == null) {
            target = new DrugInfoDto();
            target.setItemSeq(itemSeq);
            target.setItemName(nameHint);
        }

        DrugInfoDto fresh = fetchFreshDrugInfo(
                itemSeq,
                valueOrDefault(target.getItemName(), nameHint),
                requiredSections
        );
        if (fresh == null) {
            return target;
        }

        target = merge(target, fresh);
        boolean updated = overwriteAvailableSections(target, fresh);
        if (updated) {
            drugInfoDao.upsertDrugInfo(target);
            target.setUpdatedAt(LocalDateTime.now());
        }
        return target;
    }

    DrugInfoDto fetchFreshDrugInfo(
            Long itemSeq,
            String nameHint,
            Set<DrugInfoSection> requiredSections
    ) {
        DrugInfoDto fresh = fetchEasyDrugByName(nameHint);
        if (!containsRequiredSections(fresh, requiredSections)) {
            fresh = merge(fresh, scrapeNedrugByItemSeq(itemSeq));
        }
        if (fresh != null && fresh.getItemSeq() == null) {
            fresh.setItemSeq(itemSeq);
        }
        return fresh;
    }

    private boolean overwriteAvailableSections(DrugInfoDto target, DrugInfoDto fresh) {
        boolean updated = false;

        for (DrugInfoSection section : EnumSet.allOf(DrugInfoSection.class)) {
            String value = section.getValue(fresh);
            if (isBlank(value)) {
                continue;
            }
            switch (section) {
                case EFFICACY -> target.setEfcyQesitm(value);
                case USAGE -> target.setUseMethodQesitm(value);
                case WARNING -> target.setAtpnWarnQesitm(value);
                case PRECAUTION -> target.setAtpnQesitm(value);
                case INTERACTION -> target.setIntrcQesitm(value);
                case SIDE_EFFECT -> target.setSeQesitm(value);
                case STORAGE -> target.setDepositMethodQesitm(value);
            }
            updated = true;
        }
        return updated;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    @Override
    public DrugInfoDto getDrugInfo(Long itemSeq, String nameHint) throws Exception {

        Long seq = itemSeq;
        
        DrugInfoDto merged = (seq != null) ? drugInfoDao.selectByItemSeq(seq) : null;
        if (merged == null) {
            merged = new DrugInfoDto();
            merged.setItemSeq(seq);
        }

        /* 🔥 1단계: itemSeq로 낱알식별 먼저 */
        if (seq != null) {
            DrugInfoDto pill = fetchDrugPillInfo(seq);
            merged = merge(merged, pill);
        }

        if (isBlank(merged.getItemName()) && !isBlank(nameHint)) {
            merged.setItemName(nameHint);
        }

        if (!hasText(merged)) {
            DrugInfoDto easy = fetchEasyDrugByName(merged.getItemName());
            merged = merge(merged, easy);
        }

        if (!hasText(merged) && !isBlank(merged.getItemName())) {
            String n = normalizeName(merged.getItemName());
            if (!isBlank(n) && n.length() > 10) {
                String shortName = n.substring(0, 10);
                DrugInfoDto easy2 = fetchEasyDrugByName(shortName);
                merged = merge(merged, easy2);
            }
        }

        if (!hasText(merged) && !isBlank(merged.getItemName())) {
            List<DrugInfoDto> byName = searchDrug(normalizeName(merged.getItemName()));
            if (!byName.isEmpty()) {
                merged = merge(merged, byName.get(0));
            }
        }

        // ✅ 최후: nedrug 스크래핑
        if (seq != null && isMostlyEmpty(merged)) {
            System.out.println("[NEDRUG] fallback start itemSeq=" + seq + " itemName=" + merged.getItemName());

            DrugInfoDto scraped = scrapeNedrugByItemSeq(seq);

            System.out.println("[NEDRUG] fallback done itemSeq=" + seq
                    + " scraped=" + (scraped != null)
                    + " efcy=" + (scraped != null && !isBlank(scraped.getEfcyQesitm()))
                    + " usage=" + (scraped != null && !isBlank(scraped.getUseMethodQesitm()))
                    + " warn=" + (scraped != null && !isBlank(scraped.getAtpnWarnQesitm()))
                    + " intrc=" + (scraped != null && !isBlank(scraped.getIntrcQesitm()))
                    + " se=" + (scraped != null && !isBlank(scraped.getSeQesitm()))
                    + " deposit=" + (scraped != null && !isBlank(scraped.getDepositMethodQesitm()))
            );

            merged = merge(merged, scraped);
        }

        if (seq != null) {
            drugInfoDao.upsertDrugInfo(merged);
            merged.setUpdatedAt(LocalDateTime.now());
        }

        return merged;
    }

    private boolean isFresh(DrugInfoDto drug) {
        return drug != null
                && drug.getUpdatedAt() != null
                && !drug.getUpdatedAt().isBefore(LocalDateTime.now().minusDays(cacheTtlDays));
    }

    private boolean containsRequiredSections(
            DrugInfoDto drug,
            Set<DrugInfoSection> requiredSections
    ) {
        if (drug == null) {
            return false;
        }
        if (requiredSections == null || requiredSections.isEmpty()) {
            return hasText(drug);
        }
        return requiredSections.stream()
                .allMatch(section -> !isBlank(section.getValue(drug)));
    }

    /* =========================================================
       5) 상세 조회
    ========================================================= */
    @Override
    public DrugInfoDto fetchDetailByItemSeq(String itemSeq) {
        try {
            Long seq = parseLong(itemSeq);
            if (seq == null) return null;

            DrugInfoDto merged = drugInfoDao.selectByItemSeq(seq);
            if (merged == null) {
                merged = new DrugInfoDto();
                merged.setItemSeq(seq);
            }

            DrugInfoDto pill = fetchDrugPillInfo(seq);
            merged = merge(merged, pill);

            if (!hasText(merged)) {
                DrugInfoDto easy = fetchEasyDrugByName(merged.getItemName());
                merged = merge(merged, easy);
            }

            if (isMostlyEmpty(merged)) {
                DrugInfoDto scraped = scrapeNedrugByItemSeq(seq);
                merged = merge(merged, scraped);
            }

            drugInfoDao.upsertDrugInfo(merged);

            return merged;
        } catch (Exception e) {
            return null;
        }
    }

    /* =========================================================
       ✅ nedrug 스크래핑 (fallback)
       - (1) 헤딩 기반으로 efcy/useMethod/주의사항 큰 덩어리 추출
       - (2) ✅ 주의사항(큰 덩어리)에서 정규식으로 "이상반응/상호작용/보관" 잘라서 분리
    ========================================================= */
    private DrugInfoDto scrapeNedrugByItemSeq(long itemSeq) {
        String url = "https://nedrug.mfds.go.kr/pbp/CCBBB01/getItemDetail?itemSeq=" + itemSeq;

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                    .referrer("https://nedrug.mfds.go.kr/")
                    .timeout(15_000)
                    .get();

            System.out.println("[NEDRUG] fetched url=" + url + " title=" + doc.title());

            DrugInfoDto dto = new DrugInfoDto();
            dto.setItemSeq(itemSeq);

            String title = firstText(
                    doc.selectFirst("h3"),
                    doc.selectFirst("h4"),
                    doc.selectFirst(".tit"),
                    doc.selectFirst(".title")
            );
            if (!isBlank(title)) dto.setItemName(title);

            // ✅ 1차: heading 기반 추출
            String efcy = extractByHeading(doc, "효능효과");
            String usage = extractByHeading(doc, "용법용량");
            String atpn = extractByHeading(doc, "사용상의주의사항"); // 큰 덩어리로 들어올 가능성이 큼

            dto.setEfcyQesitm(efcy);
            dto.setUseMethodQesitm(usage);
            dto.setAtpnWarnQesitm(atpn);
            dto.setAtpnQesitm(atpn);

            // ✅ 2차: 주의사항에서 섹션 분리 (핵심!)
            splitFromAtpn(dto);

            // ✅ 3차: heading으로 따로 잡히는 페이지도 있으니, 비어있을 때만 보강
            if (isBlank(dto.getIntrcQesitm())) dto.setIntrcQesitm(extractByHeading(doc, "상호작용"));
            if (isBlank(dto.getDepositMethodQesitm())) dto.setDepositMethodQesitm(extractByHeading(doc, "보관"));
            if (isBlank(dto.getSeQesitm())) dto.setSeQesitm(extractByHeading(doc, "이상반응"));

            // 마지막 정리
            dto.setEfcyQesitm(normalizeBody(dto.getEfcyQesitm()));
            dto.setUseMethodQesitm(normalizeBody(dto.getUseMethodQesitm()));
            dto.setAtpnWarnQesitm(normalizeBody(dto.getAtpnWarnQesitm()));
            dto.setAtpnQesitm(normalizeBody(dto.getAtpnQesitm()));
            dto.setIntrcQesitm(normalizeBody(dto.getIntrcQesitm()));
            dto.setSeQesitm(normalizeBody(dto.getSeQesitm()));
            dto.setDepositMethodQesitm(normalizeBody(dto.getDepositMethodQesitm()));

            return dto;
        } catch (Exception e) {
            System.out.println("[NEDRUG] scrape failed url=" + url + " err=" + e.getMessage());
            return null;
        }
    }

    /**
     * ✅ "사용상의주의사항" 큰 텍스트에서
     * - 4. 이상반응 -> seQesitm
     * - 6. 상호작용 -> intrcQesitm
     * - 10. 보관 및 취급상의 주의사항 -> depositMethodQesitm
     * 를 잘라서 분리한다.
     *
     * (네가 올린 정상 결과처럼 "4. 이상반응", "6. 상호작용", "10. 보관..." 같은 번호가 실제로 포함되는 케이스가 많아서
     * 이 방식이 제일 안정적임)
     */
    private void splitFromAtpn(DrugInfoDto dto) {
        if (dto == null) return;

        String atpn = dto.getAtpnWarnQesitm();
        if (isBlank(atpn)) return;

        String se = extractBetween(
                atpn,
                "(?s)(^|\\s)4\\.?\\s*이상반응\\s*",
                "(?s)(^|\\s)(5\\.|6\\.|7\\.|8\\.|9\\.|10\\.|11\\.)\\s*"
        );

        String intrc = extractBetween(
                atpn,
                "(?s)(^|\\s)6\\.?\\s*상호작용\\s*",
                "(?s)(^|\\s)(7\\.|8\\.|9\\.|10\\.|11\\.)\\s*"
        );

        String deposit = extractBetween(
                atpn,
                "(?s)(^|\\s)10\\.?\\s*보관.*?주의사항\\s*",
                "(?s)(^|\\s)(11\\.|12\\.)\\s*"
        );

        se = normalizeSection(se, 80);
        intrc = normalizeSection(intrc, 60);
        deposit = normalizeSection(deposit, 40);

        if (!isBlank(se) && isBlank(dto.getSeQesitm())) {
            dto.setSeQesitm(se);
        }
        if (!isBlank(intrc) && isBlank(dto.getIntrcQesitm())) {
            dto.setIntrcQesitm(intrc);
        }
        if (!isBlank(deposit) && isBlank(dto.getDepositMethodQesitm())) {
            dto.setDepositMethodQesitm(deposit);
        }

        String cleanedAtpn = atpn;
        if (!isBlank(se)) cleanedAtpn = cleanedAtpn.replace(se, " ");
        if (!isBlank(intrc)) cleanedAtpn = cleanedAtpn.replace(intrc, " ");
        if (!isBlank(deposit)) cleanedAtpn = cleanedAtpn.replace(deposit, " ");

        cleanedAtpn = cleanedAtpn
                .replaceAll("\\s+", " ")
                .trim();

        dto.setAtpnWarnQesitm(cleanedAtpn);
        dto.setAtpnQesitm(cleanedAtpn);
    }
    
    private String normalizeSection(String raw, int minLength) {
        if (isBlank(raw)) return null;

        String t = raw
                .replaceAll("(PDF|XML|HTML)다운로드", " ")
                .replaceAll("변경이력", " ")
                .replaceAll("폴딩\\s*버튼", " ")
                .replaceAll(":+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        // 너무 짧거나 표 머리 수준이면 버림
        if (t.length() < minLength) return null;

        return t;
    }

    private String extractBetween(String text, String startRegex, String endRegex) {
        if (isBlank(text)) return "";
        Pattern pStart = Pattern.compile(startRegex);
        Matcher mStart = pStart.matcher(text);
        if (!mStart.find()) return "";

        int startIdx = mStart.end();

        Pattern pEnd = Pattern.compile(endRegex);
        Matcher mEnd = pEnd.matcher(text);
        if (mEnd.find(startIdx)) {
            int endIdx = mEnd.start();
            return text.substring(startIdx, endIdx).trim();
        }
        return text.substring(startIdx).trim();
    }

    private String firstText(Element... els) {
        for (Element el : els) {
            if (el != null) {
                String t = el.text();
                if (t != null && !t.trim().isEmpty()) return t.trim();
            }
        }
        return "";
    }

    /**
     * ✅ heading 찾기 튼튼 버전:
     * - doc 전체에서 "효능효과" 같은 헤딩 텍스트를 가진 요소를 찾고
     * - 그 주변에서 '길이가 긴' 블록을 우선 반환
     */
    private String extractByHeading(Document doc, String heading) {
        if (doc == null || isBlank(heading)) return "";

        // 1) 정확히 "효능효과" 같은 텍스트만 가진 요소 찾기 (공백 허용)
        String regex = "^\\s*" + Pattern.quote(heading) + "\\s*$";
        Element header = doc.selectFirst("*:matchesOwn(" + regex + ")");
        if (header == null) {
            // 2) 못 찾으면 contains로 한 번 더
            Elements candidates = doc.getElementsMatchingOwnText(Pattern.compile(Pattern.quote(heading)));
            header = candidates.isEmpty() ? null : candidates.first();
        }
        if (header == null) return "";

        // 3) 본문 후보 탐색: header의 부모/조상에서 "긴 텍스트"를 찾는다
        Element cur = header;
        for (int up = 0; up < 4 && cur != null; up++) {
            Element parent = cur.parent();
            if (parent == null) break;

            // (1) parent 다음 형제들을 훑어보며 긴 텍스트 블록 찾기
            Element sib = parent.nextElementSibling();
            for (int i = 0; i < 10 && sib != null; i++) {
                String t = cleanSectionText(sib.text(), heading);
                if (t.length() >= 80) return t;
                sib = sib.nextElementSibling();
            }

            // (2) parent 내부에서도 긴 텍스트 찾기
            String best = "";
            for (Element child : parent.children()) {
                String t = cleanSectionText(child.text(), heading);
                if (t.length() > best.length()) best = t;
            }
            if (best.length() >= 80) return best;

            cur = parent;
        }

        // 4) 최후 fallback
        Element p = header.parent();
        String fallback = (p != null) ? p.text() : header.text();
        return cleanSectionText(fallback, heading);
    }

    private String cleanSectionText(String raw, String heading) {
        if (raw == null) return "";
        String t = raw.trim();

        // 잡음 제거
        if (!isBlank(heading)) t = t.replace(heading, " ").trim();
        t = t.replaceAll("폴딩\\s*버튼", " ").trim();
        t = t.replaceAll("(PDF|XML|HTML)다운로드", " ").trim();
        t = t.replaceAll("변경이력", " ").trim();
        t = t.replaceAll("\\s+", " ").trim();

        return t;
    }

    private String normalizeBody(String s) {
        if (s == null) return "";
        String t = s.trim();

        // 화면에서 이상한 ::: 같은 토큰이 튀는 케이스 방지
        t = t.replaceAll("\\s*:::+\\s*", " ").trim();
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private boolean isMostlyEmpty(DrugInfoDto d) {
        if (d == null) return true;
        int empty = 0;

        if (isBlank(d.getEfcyQesitm())) empty++;
        if (isBlank(d.getUseMethodQesitm())) empty++;
        if (isBlank(d.getAtpnWarnQesitm())) empty++;
        if (isBlank(d.getSeQesitm())) empty++;

        return empty >= 2;
    }

    /* =========================================================
       공통 유틸
    ========================================================= */
    private String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private DrugInfoDto parseDrugInfo(JSONObject obj) {
        DrugInfoDto d = new DrugInfoDto();

        Long seq = parseLong(pick(obj, "itemSeq", "ITEM_SEQ", "item_seq"));
        d.setItemSeq(seq);

        d.setItemName(pick(obj, "itemName", "ITEM_NAME", "item_name"));
        d.setEntpName(pick(obj, "entpName", "ENTP_NAME", "entp_name"));
        d.setIngrName(pick(obj, "ingrName", "INGR_NAME", "ingr_name"));
        d.setItemImage(pick(obj, "itemImage", "ITEM_IMAGE", "item_image"));

        d.setDrugShape(pick(obj, "drugShape", "DRUG_SHAPE", "drug_shape"));
        d.setColorClass1(pick(obj, "colorClass1", "COLOR_CLASS1", "color_class1"));
        d.setColorClass2(pick(obj, "colorClass2", "COLOR_CLASS2", "color_class2"));
        d.setPrintFront(pick(obj, "printFront", "PRINT_FRONT", "print_front"));
        d.setPrintBack(pick(obj, "printBack", "PRINT_BACK", "print_back"));
        d.setLineFront(pick(obj, "lineFront", "LINE_FRONT", "line_front"));
        d.setLineBack(pick(obj, "lineBack", "LINE_BACK", "line_back"));

        d.setEfcyQesitm(pick(obj, "efcyQesitm", "EFCY_QESITM", "efcy_qesitm"));
        d.setUseMethodQesitm(pick(obj, "useMethodQesitm", "USE_METHOD_QESITM", "use_method_qesitm"));
        d.setAtpnWarnQesitm(pick(obj, "atpnWarnQesitm", "ATPN_WARN_QESITM", "atpn_warn_qesitm"));
        d.setAtpnQesitm(pick(obj, "atpnQesitm", "ATPN_QESITM", "atpn_qesitm"));
        d.setIntrcQesitm(pick(obj, "intrcQesitm", "INTRC_QESITM", "intrc_qesitm"));
        d.setSeQesitm(pick(obj, "seQesitm", "SE_QESITM", "se_qesitm"));
        d.setDepositMethodQesitm(pick(obj, "depositMethodQesitm", "DEPOSIT_METHOD_QESITM", "deposit_method_qesitm"));

        return d;
    }

    private JSONObject callApi(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        if (conn.getResponseCode() != 200) return null;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String result = br.lines().collect(Collectors.joining());
            JSONObject root = new JSONObject(result);

            JSONObject response = root.optJSONObject("response");
            if (response != null) return response.optJSONObject("body");

            return root.optJSONObject("body");
        }
    }

    private JSONArray extractItems(JSONObject body) {
        if (body == null) return null;

        Object itemsObj = body.opt("items");
        if (itemsObj instanceof JSONArray arr) return arr;

        if (itemsObj instanceof JSONObject itemsJson) {
            Object itemObj = itemsJson.opt("item");
            if (itemObj instanceof JSONArray arr2) return arr2;
            if (itemObj instanceof JSONObject one) {
                JSONArray arr3 = new JSONArray();
                arr3.put(one);
                return arr3;
            }
        }
        return null;
    }

    private DrugInfoDto merge(DrugInfoDto a, DrugInfoDto b) {
        if (b == null) return a;
        if (a == null) return b;

        if (isBlank(a.getItemName())) a.setItemName(b.getItemName());
        if (isBlank(a.getEntpName())) a.setEntpName(b.getEntpName());
        if (isBlank(a.getIngrName())) a.setIngrName(b.getIngrName());
        if (isBlank(a.getItemImage())) a.setItemImage(b.getItemImage());

        if (isBlank(a.getDrugShape())) a.setDrugShape(b.getDrugShape());
        if (isBlank(a.getColorClass1())) a.setColorClass1(b.getColorClass1());
        if (isBlank(a.getColorClass2())) a.setColorClass2(b.getColorClass2());
        if (isBlank(a.getPrintFront())) a.setPrintFront(b.getPrintFront());
        if (isBlank(a.getPrintBack())) a.setPrintBack(b.getPrintBack());
        if (isBlank(a.getLineFront())) a.setLineFront(b.getLineFront());
        if (isBlank(a.getLineBack())) a.setLineBack(b.getLineBack());

        if (isBlank(a.getEfcyQesitm())) a.setEfcyQesitm(b.getEfcyQesitm());
        if (isBlank(a.getUseMethodQesitm())) a.setUseMethodQesitm(b.getUseMethodQesitm());
        if (isBlank(a.getAtpnWarnQesitm())) a.setAtpnWarnQesitm(b.getAtpnWarnQesitm());
        if (isBlank(a.getAtpnQesitm())) a.setAtpnQesitm(b.getAtpnQesitm());
        if (isBlank(a.getIntrcQesitm())) a.setIntrcQesitm(b.getIntrcQesitm());
        if (isBlank(a.getSeQesitm())) a.setSeQesitm(b.getSeQesitm());
        if (isBlank(a.getDepositMethodQesitm())) a.setDepositMethodQesitm(b.getDepositMethodQesitm());

        return a;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private boolean hasText(DrugInfoDto d) {
        return d != null && (!isBlank(d.getEfcyQesitm())
                || !isBlank(d.getUseMethodQesitm())
                || !isBlank(d.getAtpnWarnQesitm()));
    }

    private Long parseLong(String s) {
        try {
            return (s == null) ? null : Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeName(String name) {
        if (name == null) return null;
        String n = name.trim();
        n = n.replaceAll("\\(.*?\\)", "").trim();
        n = n.replaceAll("\\s+", " ");
        return n;
    }

    @Override
    public List<DrugInfoDto> getDrugInfoListByItemSeq(List<Long> itemSeqList) {
        if (itemSeqList == null || itemSeqList.isEmpty()) {
            return List.of();
        }
        return drugInfoDao.selectByItemSeqList(itemSeqList);
    }
}
