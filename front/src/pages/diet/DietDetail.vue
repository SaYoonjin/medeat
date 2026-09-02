<script setup>
import { ref, onMounted, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import dietApi from "@/api/diet";
import { 
  Sparkles, 
  Clock, 
  Calendar as CalIcon, 
  Edit3, 
  Trash2, 
  ArrowLeft,
  Zap,
  Droplets,
  Flame,
  Utensils,
  FileText,
  ChevronRight,
  Info,
  Loader2
} from "lucide-vue-next";

const router = useRouter();
const route = useRoute();

const log = ref(null);
const loading = ref(true);

// 수치 반올림 헬퍼
const formatNum = (num) => {
  if (num === null || num === undefined || isNaN(num)) return 0;
  return parseFloat(Number(num).toFixed(2));
};

// 사진 URL
const imageUrl = computed(() => {
  if (!log.value || !log.value.photoPath) return null;
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
  return `${baseUrl}${log.value.photoPath}`;
});

// 총합 계산 및 반올림 적용
const totalKcal = computed(() => {
  if (!log.value?.items) return 0;
  return formatNum(log.value.items.reduce((sum, it) => sum + (it.calorie || 0), 0));
});
const totalCarb = computed(() => {
  if (!log.value?.items) return 0;
  return formatNum(log.value.items.reduce((sum, it) => sum + (it.carb || 0), 0));
});
const totalProtein = computed(() => {
  if (!log.value?.items) return 0;
  return formatNum(log.value.items.reduce((sum, it) => sum + (it.protein || 0), 0));
});
const totalFat = computed(() => {
  if (!log.value?.items) return 0;
  return formatNum(log.value.items.reduce((sum, it) => sum + (it.fat || 0), 0));
});

// 데이터 로딩
onMounted(async () => {
  try {
    const id = route.params.id;
    const res = await dietApi.getDetail(id);
    log.value = res.data;
    if (!log.value.items) log.value.items = [];
  } catch (e) {
    console.error("데이터 로드 실패:", e);
  } finally {
    loading.value = false;
  }
});

// 음식 삭제
const removeItem = async (itemId, index) => {
  if (!confirm("정말 이 음식을 삭제할까요?")) return;
  try {
    await dietApi.deleteItem(itemId);
    log.value.items.splice(index, 1);
  } catch (e) {
    console.error(e);
  }
};

const goEdit = () => {
  router.push({ name: "diet-edit", params: { id: route.params.id } });
};

const goBack = () => {
  router.push({ path: "/diet", query: { mode: "EAT" } });
};
</script>

<template>
  <div class="intro-container">
    <!-- 배경 장식 -->
    <div class="blob-eat"></div>

    <main class="content-wrapper" v-if="log">
      <div class="detail-card glass-card">
        <!-- 상단 헤더 -->
        <header class="page-header">
          <div class="header-navigation">
            <button class="back-btn" @click="goBack">
              <ArrowLeft :size="18" />
              <span>LIST</span>
            </button>
            <button class="edit-circle-btn" @click="goEdit">
              <Edit3 :size="18" />
            </button>
          </div>
          
          <div class="header-content mt-6">
            <div class="badge-centered">
              <Sparkles :size="12" class="icon-purple" />
              <span>MEAL INSIGHT</span>
            </div>
            <h2 class="hero-title italic centered">
              MEAL<br />
              <span class="gradient-text">DETAILS.</span>
            </h2>
            <p class="hero-subtext centered">기록된 식단의 영양 성분과 상세 정보를 확인하세요.</p>
            
            <div class="date-pill-container mt-12">
              <div class="top-date-pill">
                <CalIcon :size="14" class="icon-lime" />
                <span>{{ log.logDate }}</span>
              </div>
            </div>
          </div>
        </header>

        <div class="main-content-grid mt-12">
          <!-- 왼쪽: 비주얼 영역 (사진) -->
          <div class="info-visual-section">
            <div v-if="imageUrl" class="photo-display-card">
              <img :src="imageUrl" alt="Diet Photo" class="meal-photo" />
              <div class="photo-overlay-info">
                <span class="meal-time-tag">{{ log.mealTime }}</span>
              </div>
            </div>
            <div v-else class="photo-placeholder-card">
              <Utensils :size="64" class="opacity-10" />
              <p>{{ log.mealTime }} 식단</p>
            </div>
          </div>

          <!-- 오른쪽: 요약 및 리스트 -->
          <div class="info-list-section">
            <section class="nutrition-dashboard">
              <div class="total-kcal-box">
                <div class="kcal-label">TOTAL CALORIES</div>
                <div class="kcal-value-wrap">
                  <span class="value">{{ totalKcal }}</span>
                  <span class="unit">kcal</span>
                </div>
              </div>

              <div class="macro-grid mt-6">
                <div class="macro-item carb">
                  <div class="m-icon"><Zap :size="12" /></div>
                  <span class="m-val">{{ totalCarb }}g</span>
                  <span class="m-label">탄수화물</span>
                </div>
                <div class="macro-item protein">
                  <div class="m-icon"><Droplets :size="12" /></div>
                  <span class="m-val">{{ totalProtein }}g</span>
                  <span class="m-label">단백질</span>
                </div>
                <div class="macro-item fat">
                  <div class="m-icon"><Flame :size="12" /></div>
                  <span class="m-val">{{ totalFat }}g</span>
                  <span class="m-label">지방</span>
                </div>
              </div>
            </section>

            <!-- 음식 목록 -->
            <section class="food-list-area mt-12">
              <div class="section-header-row">
                <h3 class="section-title italic">FOOD LIST</h3>
                <span class="count-badge">{{ log.items.length }} ITEMS</span>
              </div>

              <!-- 🌟 헤더와 리스트 사이 간격 mt-8에서 mt-10으로 살짝 확대 -->
              <div v-if="log.items.length" class="items-stack mt-12">
                <TransitionGroup name="tag-pop">
                  <article v-for="(it, idx) in log.items" :key="it.itemId" class="item-row-card">
                    <div class="item-main">
                      <div class="item-head">
                        <h4 class="item-name">{{ it.foodName }}</h4>
                        <span class="item-kcal">{{ it.calorie }} kcal</span>
                      </div>
                      <div class="item-nutri-line">
                        <span>탄 {{ it.carb }}g</span>
                        <span class="dot"></span>
                        <span>단 {{ it.protein }}g</span>
                        <span class="dot"></span>
                        <span>지 {{ it.fat }}g</span>
                      </div>
                    </div>
                    <button class="item-delete-btn" @click="removeItem(it.itemId, idx)">
                      <Trash2 :size="14" />
                    </button>
                  </article>
                </TransitionGroup>
              </div>

              <div v-else class="empty-items mt-10">
                <p>등록된 음식이 없습니다.</p>
              </div>
            </section>
          </div>
        </div>

        <!-- 하단 메모 -->
        <section class="memo-area-full mt-14">
          <div class="section-title italic mb-6">MEMO</div>
          <div class="memo-bubble-full">
            <FileText :size="18" class="memo-icon" />
            <p class="memo-text-content">{{ log.memo || "작성된 메모가 없습니다." }}</p>
          </div>
          <div class="guide-box-footer mt-8">
            <Info :size="14" />
            <span>영양 성분은 입력된 중량(g)에 기반한 분석 결과입니다.</span>
          </div>
        </section>
      </div>
    </main>

    <div v-else-if="loading" class="loading-state">
      <Loader2 :size="40" class="animate-spin" />
    </div>
  </div>
</template>

<style scoped>
/* 인트로 레이아웃 계승 */
.intro-container {
  background-color: #f8f9fc;
  color: #1a1a1a;
  min-height: 100vh;
  position: relative;
  overflow-x: hidden;
  padding: 100px 24px 80px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}

.blob-eat {
  position: fixed;
  top: -10%;
  left: -10%;
  width: 70vw;
  height: 70vw;
  background: radial-gradient(circle, rgba(163, 230, 53, 0.15) 0%, transparent 70%);
  border-radius: 50%;
  filter: blur(140px);
  z-index: 0;
  pointer-events: none;
  animation: blobMove 20s infinite alternate ease-in-out;
}

@keyframes blobMove {
  0% { transform: translate(0, 0); }
  100% { transform: translate(8%, 4%); }
}

.content-wrapper {
  width: 100%;
  max-width: 1100px;
  position: relative;
  z-index: 10;
}

.glass-card {
  background: rgba(255, 255, 255, 0.45);
  backdrop-filter: blur(30px);
  -webkit-backdrop-filter: blur(30px);
  border: 1px solid rgba(255, 255, 255, 0.8);
  border-radius: 60px;
  padding: 60px 50px;
  box-shadow: 0 40px 100px rgba(0, 0, 0, 0.02);
}

/* Header 스타일 */
.header-navigation { display: flex; justify-content: space-between; align-items: center; }
.back-btn { display: flex; align-items: center; gap: 8px; background: none; border: none; font-weight: 850; font-size: 0.8rem; color: #94a3b8; cursor: pointer; transition: 0.3s; }
.back-btn:hover { color: #1a1a1a; transform: translateX(-5px); }

/* 날짜 피크 컨테이너 */
.date-pill-container { display: flex; justify-content: center; }
.top-date-pill { display: flex; align-items: center; gap: 8px; padding: 10px 20px; border-radius: 999px; background: white; border: 1.5px solid #f1f5f9; font-size: 0.9rem; font-weight: 850; color: #1a1a1a; box-shadow: 0 4px 12px rgba(0,0,0,0.02); }
.icon-lime { color: #a3e635; }

.edit-circle-btn { width: 46px; height: 46px; border-radius: 50%; background: #1a1a1a; color: white; border: none; display: flex; align-items: center; justify-content: center; cursor: pointer; transition: 0.3s; }
.edit-circle-btn:hover { background: #a3e635; transform: scale(1.1) rotate(15deg); }

.badge-centered { display: flex; justify-content: center; align-items: center; gap: 6px; padding: 6px 14px; background: white; border-radius: 999px; font-size: 0.65rem; font-weight: 850; color: #7c4dff; border: 1.5px solid #f1f5f9; width: fit-content; margin: 0 auto 20px; }

.hero-title { font-size: 3.5rem; line-height: 0.95; font-weight: 950; letter-spacing: -0.05em; margin-bottom: 20px; }
.hero-title.centered { text-align: center; }
.gradient-text { background: linear-gradient(to bottom right, #1a1a1a, #a3e635); -webkit-background-clip: text; background-clip: text; color: transparent; }
.hero-subtext { font-size: 1.1rem; opacity: 0.6; font-weight: 500; }
.hero-subtext.centered { text-align: center; }

/* 메인 컨텐츠 그리드 */
.main-content-grid { display: grid; grid-template-columns: 1fr 1.3fr; gap: 50px; align-items: start; }

/* 사진 섹션 */
.photo-display-card { position: relative; border-radius: 40px; overflow: hidden; box-shadow: 0 20px 50px rgba(0,0,0,0.08); background: #f8fafc; }
.meal-photo { width: 100%; height: 500px; object-fit: cover; transition: 0.6s cubic-bezier(0.23, 1, 0.32, 1); }
.photo-display-card:hover .meal-photo { transform: scale(1.04); }
.photo-overlay-info { position: absolute; bottom: 24px; left: 24px; }
.meal-time-tag { background: rgba(0,0,0,0.7); backdrop-filter: blur(12px); color: white; padding: 8px 20px; border-radius: 999px; font-weight: 850; font-size: 0.85rem; letter-spacing: 0.1em; }

.photo-placeholder-card { width: 100%; height: 400px; border-radius: 40px; background: rgba(0,0,0,0.02); border: 2px dashed #e2e8f0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; color: #cbd5e1; font-weight: 850; }

/* 오른쪽 섹션 (칼로리 + 리스트) */
.info-list-section { display: flex; flex-direction: column; }

.nutrition-dashboard { background: white; border-radius: 40px; padding: 40px; border: 1.5px solid #f1f5f9; box-shadow: 0 15px 35px rgba(0,0,0,0.02); }
.kcal-label { font-size: 0.8rem; font-weight: 950; color: #cbd5e1; letter-spacing: 0.15em; margin-bottom: 12px; }
.kcal-value-wrap { display: flex; align-items: baseline; gap: 8px; }
.kcal-value-wrap .value { font-size: 3.5rem; font-weight: 950; color: #1a1a1a; line-height: 0.9; }
.kcal-value-wrap .unit { font-size: 1.2rem; font-weight: 850; color: #94a3b8; }

.macro-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.macro-item { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 16px 8px; border-radius: 24px; background: #f8fafc; border: 1.5px solid transparent; transition: 0.3s; }
.m-icon { width: 28px; height: 28px; border-radius: 50%; display: flex; align-items: center; justify-content: center; margin-bottom: 4px; }
.carb .m-icon { background: rgba(163, 230, 53, 0.15); color: #84cc16; }
.protein .m-icon { background: rgba(56, 189, 248, 0.15); color: #0ea5e9; }
.fat .m-icon { background: rgba(244, 63, 94, 0.15); color: #f43f5e; }
.m-val { font-size: 1.05rem; font-weight: 950; color: #1a1a1a; }
.m-label { font-size: 0.7rem; font-weight: 850; color: #94a3b8; }

/* 음식 리스트 */
.section-header-row { display: flex; justify-content: space-between; align-items: center; }
.section-title { font-size: 0.95rem; font-weight: 950; color: #1a1a1a; letter-spacing: 0.1em; }
.count-badge { background: #f1f5f9; color: #64748b; padding: 4px 14px; border-radius: 12px; font-size: 0.75rem; font-weight: 900; }

.items-stack { display: flex; flex-direction: column; gap: 12px; max-height: 400px; overflow-y: auto; padding-right: 8px; }
.items-stack::-webkit-scrollbar { width: 4px; }
.items-stack::-webkit-scrollbar-thumb { background: #e2e8f0; border-radius: 10px; }

.item-row-card { display: flex; justify-content: space-between; align-items: center; padding: 20px 24px; background: white; border-radius: 28px; border: 1.5px solid #f1f5f9; transition: 0.3s; }
.item-row-card:hover { transform: translateX(8px); border-color: #a3e635; box-shadow: 0 10px 25px rgba(163, 230, 53, 0.05); }

.item-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 6px; }
.item-name { font-size: 1.1rem; font-weight: 900; color: #334155; }
.item-kcal { font-size: 0.9rem; font-weight: 850; color: #f43f5e; }

.item-nutri-line { display: flex; align-items: center; gap: 8px; font-size: 0.8rem; font-weight: 700; color: #94a3b8; }
.item-nutri-line .dot { width: 3px; height: 3px; background: #e2e8f0; border-radius: 50%; }

.item-delete-btn { width: 34px; height: 34px; border-radius: 10px; background: #fef2f2; border: none; color: #f43f5e; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: 0.2s; opacity: 0; }
.item-row-card:hover .item-delete-btn { opacity: 1; }
.item-delete-btn:hover { background: #f43f5e; color: white; transform: rotate(90deg); }

.empty-items { text-align: center; padding: 40px 0; color: #cbd5e1; font-weight: 850; font-size: 0.9rem; background: rgba(0,0,0,0.01); border-radius: 24px; border: 1.5px dashed #f1f5f9; }

/* 하단 메모 */
.memo-area-full { border-top: 1px solid #f1f5f9; padding-top: 40px; }
.memo-bubble-full { background: #f8fafc; border-radius: 30px; padding: 32px 40px; border: 1.5px solid #f1f5f9; display: flex; gap: 24px; box-shadow: inset 0 2px 4px rgba(0,0,0,0.01); }
.memo-icon { color: #cbd5e1; margin-top: 4px; }
.memo-text-content { font-size: 1.1rem; line-height: 1.8; font-weight: 600; color: #475569; }

.guide-box-footer { display: flex; align-items: center; gap: 10px; font-size: 0.8rem; font-weight: 750; color: #cbd5e1; padding-left: 20px; }

/* 로딩 상태 */
.loading-state { min-height: 60vh; display: flex; align-items: center; justify-content: center; color: #a3e635; }
.animate-spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

.italic { font-style: italic; }
.mt-12 { margin-top: 2rem; }
.mt-20 { margin-top: 5rem; }
.mb-6 { margin-bottom: 1.5rem; }

@media (max-width: 1000px) {
  .main-content-grid { grid-template-columns: 1fr; gap: 40px; }
  .glass-card { padding: 40px 24px; border-radius: 40px; }
  .hero-title { font-size: 2.8rem; }
  .meal-photo { height: 350px; }
}

/* 애니메이션 */
.tag-pop-enter-active { animation: tagPop 0.4s cubic-bezier(0.23, 1, 0.32, 1); }
.tag-pop-leave-active { animation: tagPop 0.3s reverse ease-in; }
@keyframes tagPop {
  0% { opacity: 0; transform: scale(0.5) translateY(10px); }
  100% { opacity: 1; transform: scale(1) translateY(0); }
}
</style>
