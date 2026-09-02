<script setup>
import { ref, reactive, onMounted, computed, watch, toRaw } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import dietApi from '@/api/diet'
import { 
  Sparkles, 
  Calendar as CalIcon, 
  Utensils, 
  Search, 
  Plus, 
  X, 
  Camera, 
  Save, 
  ArrowRight,
  Loader2,
  Trash2,
  FileText,
  Zap,
  Droplets,
  Flame,
  ChevronRight,
  Info
} from 'lucide-vue-next'

const router = useRouter()
const route = useRoute()

/* =========================
   🌟 테마 설정 (MyActivity 스타일 이식)
========================= */
const currentMode = computed(() => {
  const m = (route.query.mode || 'EAT').toString().toUpperCase()
  return m === 'MEDI_EAT' ? 'MEDI_EAT' : 'EAT'
})

const themeColors = {
  EAT: {
    primary: "#a3e635", // 연두색
    soft: "rgba(163, 230, 53, 0.15)",
    glow: "rgba(163, 230, 53, 0.25)"
  },
  MEDI_EAT: {
    primary: "#38bdf8", // 하늘색
    soft: "rgba(56, 189, 248, 0.15)",
    glow: "rgba(56, 189, 248, 0.25)"
  }
};

const activeTheme = computed(() => themeColors[currentMode.value]);

/* -----------------------------
   상태 변수 (기존 로직 유지)
----------------------------- */
const log = reactive({
  dietId: null, 
  logDate: '', 
  mealTime: '', 
  memo: '', 
  photoPath: '', 
  items: []
})

const mealOptions = ['아침', '점심', '저녁', '간식', '야식']
const foodKeyword = ref('')
const searchResults = ref([])
const searching = ref(false)
const photoFile = ref(null)
const photoPreview = ref(null)

const canSave = computed(() => log.logDate && log.mealTime && log.items.length > 0)

onMounted(async () => {
  const dietId = route.params.id
  if (dietId) {
    try {
      const res = await dietApi.getDetail(dietId)
      Object.assign(log, res.data)
      if (log.photoPath) {
        photoPreview.value = `${import.meta.env.VITE_API_BASE_URL || ''}${log.photoPath}`
      }
    } catch (e) {
      console.error('기록 로드 실패', e)
    }
  } else {
    log.logDate = route.query.date || new Date().toISOString().slice(0, 10)
  }
})

/* -----------------------------
   비즈니스 로직
----------------------------- */
const searchFood = async () => {
  if (!foodKeyword.value.trim()) return
  searching.value = true
  try {
    const res = await dietApi.searchFood(foodKeyword.value)
    searchResults.value = res.data || []
  } catch (e) { 
    console.error(e) 
    searchResults.value = []
  } finally {
    searching.value = false
  }
}

const addFood = (f) => {
  log.items.push({
    foodId: f.foodId, 
    foodName: f.name, 
    gram: f.servingGram || 100,
    calorie: f.calorie || 0, 
    carb: f.carb || 0, 
    protein: f.protein || 0, 
    fat: f.fat || 0,
    baseGram: f.servingGram || 100, 
    baseCalorie: f.calorie || 0,
    baseCarb: f.carb || 0, 
    baseProtein: f.protein || 0, 
    baseFat: f.fat || 0
  })
  searchResults.value = []
  foodKeyword.value = ''
}

const addDirectFood = () => {
  const name = foodKeyword.value.trim()
  if (!name) return

  log.items.push({
    foodId: null, 
    foodName: name,
    gram: 100,
    calorie: 0,
    carb: 0,
    protein: 0,
    fat: 0,
    baseGram: 100,
    baseCalorie: 0,
    baseCarb: 0,
    baseProtein: 0,
    baseFat: 0
  })
  
  foodKeyword.value = ''
  searchResults.value = []
}

const recalc = (item, mode) => {
  const baseValue = item[`base${mode.charAt(0).toUpperCase() + mode.slice(1)}`]
  if (!baseValue || baseValue === 0) return 
  
  const r = item[mode] / baseValue
  const fields = ['gram', 'calorie', 'carb', 'protein', 'fat']
  fields.forEach(f => { 
    if (f !== mode) {
      const baseField = `base${f.charAt(0).toUpperCase() + f.slice(1)}`
      item[f] = +(item[baseField] * r).toFixed(2) 
    }
  })
}

const removeItem = (idx) => log.items.splice(idx, 1)

const onFileChange = (e) => {
  const file = e.target.files[0]
  if (file) { 
    photoFile.value = file
    photoPreview.value = URL.createObjectURL(file) 
  }
}

const saveDiet = async () => {
  if (!canSave.value) return

  const formData = new FormData()
  const payload = {
    ...toRaw(log),
    items: log.items.map(i => ({ ...toRaw(i) }))
  }

  formData.append('diet', new Blob([JSON.stringify(payload)], { type: 'application/json' }))
  if (photoFile.value) formData.append('photo', photoFile.value)

  try {
    await (log.dietId ? dietApi.update(log.dietId, formData) : dietApi.save(formData))
    router.push({ path: '/diet', query: { mode: currentMode.value } })
  } catch (e) {
    console.error(e)
  }
}

const goBack = () => router.back()
</script>

<template>
  <div class="intro-container" :style="{ '--point-color': activeTheme.primary, '--point-soft': activeTheme.soft }">
    
    <!-- 배경 장식 (모드 동기화) -->
    <TransitionGroup name="fade-blob">
      <div v-if="currentMode === 'EAT'" key="eat-blob" class="blob blob-eat"></div>
      <div v-if="currentMode === 'MEDI_EAT'" key="medi-blob" class="blob blob-medi"></div>
    </TransitionGroup>

    <main class="content-wrapper">
      <div class="form-card glass-card">
        <!-- 상단 헤더 -->
        <header class="page-header">
          <div class="badge">
            <Sparkles :size="12" class="icon-purple" />
            <span>DIET LOGGING</span>
          </div>
          <h2 class="hero-title italic">
            LOG YOUR<br />
            <span class="gradient-text">MEAL.</span>
          </h2>
          <p class="hero-subtext">오늘 드신 음식을 기록하고 영양 성분을 분석해보세요.</p>
        </header>

        <div class="form-container">
          <!-- 1. 기본 정보 설정 -->
          <section class="form-section">
            <h3 class="section-title italic">1. WHEN DID YOU EAT?</h3>
            <div class="input-row mt-8">
              <div class="input-group date-picker-group">
                <label>날짜 선택</label>
                <div class="modern-input-box">
                  <CalIcon :size="18" class="input-icon" />
                  <input type="date" v-model="log.logDate" />
                </div>
              </div>
              <div class="input-group meal-selection-group">
                <label>끼니 구분</label>
                <div class="chip-group">
                  <button 
                    v-for="m in mealOptions" 
                    :key="m" 
                    type="button" 
                    class="mode-chip" 
                    :class="{ active: log.mealTime === m }" 
                    @click="log.mealTime = m"
                  >
                    {{ m }}
                  </button>
                </div>
              </div>
            </div>
          </section>

          <!-- 2. 음식 검색 및 추가 -->
          <section class="form-section mt-10">
            <h3 class="section-title italic">2. SEARCH FOOD</h3>
            <div class="search-wrapper mt-6">
              <div class="search-bar-modern">
                <Search :size="20" class="search-icon-fixed" />
                <input 
                  v-model="foodKeyword" 
                  placeholder="피자, 샐러드, 김치찌개 등..." 
                  @keyup.enter="searchFood" 
                />
                <button class="search-action-btn" @click="searchFood" :disabled="searching">
                  <Loader2 v-if="searching" :size="18" class="animate-spin" />
                  <span v-else class="btn-search-label">SEARCH</span>
                </button>
              </div>

              <!-- 검색 결과 인라인 드롭다운 -->
              <Transition name="expand">
                <div v-if="searchResults.length > 0" class="search-results-inline">
                  <div class="results-header">검색 결과 ({{ searchResults.length }})</div>
                  <div class="mini-result-list custom-scroll">
                    <div 
                      v-for="f in searchResults" 
                      :key="f.foodId" 
                      class="mini-item-inline" 
                      @click="addFood(f)"
                    >
                      <div class="item-info">
                        <span class="name">{{ f.name }}</span>
                        <span class="corp">100g 기준</span>
                      </div>
                      <div class="item-right">
                        <div class="kcal-pill">
                          <span class="kcal-val">{{ f.calorie }}</span>
                          <span class="kcal-unit">kcal</span>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </Transition>

              <!-- 🌟 중앙 정렬된 직접 입력 유도 -->
              <div class="direct-add-hint mt-10" v-if="foodKeyword && !searching && searchResults.length === 0">
                <div class="hint-text font-black">원하는 검색 결과가 없나요?</div>
                <button class="btn-direct font-black mt-3" @click="addDirectFood">
                  "{{ foodKeyword }}" 직접 추가하기
                  <Plus :size="14" class="ml-1" />
                </button>
              </div>
            </div>
          </section>

          <!-- 3. 선택된 음식 리스트 -->
          <section class="form-section mt-10">
            <div class="section-header-row">
              <h3 class="section-title italic">3. SELECTED LIST</h3>
              <span class="item-count-badge" v-if="log.items.length">{{ log.items.length }} ITEMS</span>
            </div>

            <div v-if="log.items.length === 0" class="empty-list-card mt-12">
              <Utensils :size="32" class="opacity-20" />
              <p>아직 추가된 음식이 없습니다.<br/>검색을 통해 식단을 구성해 보세요.</p>
            </div>

            <div v-else class="selected-items-grid mt-12">
              <TransitionGroup name="tag-pop">
                <article v-for="(it, idx) in log.items" :key="idx" class="food-item-card">
                  <div class="card-top">
                    <div class="food-title">
                      <span class="dot"></span>
                      <h4 class="font-black">{{ it.foodName }}</h4>
                    </div>
                    <button class="remove-btn" @click="removeItem(idx)">
                      <X :size="14" />
                    </button>
                  </div>

                  <div class="card-content">
                    <div class="input-grid">
                      <div class="nutri-input">
                        <label>양(g)</label>
                        <input type="number" v-model.number="it.gram" @input="recalc(it, 'gram')" />
                      </div>
                      <div class="nutri-input kcal">
                        <label>칼로리</label>
                        <input type="number" v-model.number="it.calorie" @input="recalc(it, 'calorie')" />
                      </div>
                      <div class="nutri-input carb">
                        <label>탄</label>
                        <input type="number" v-model.number="it.carb" step="0.1" @input="recalc(it, 'carb')" />
                      </div>
                      <div class="nutri-input protein">
                        <label>단</label>
                        <input type="number" v-model.number="it.protein" step="0.1" @input="recalc(it, 'protein')" />
                      </div>
                      <div class="nutri-input fat">
                        <label>지</label>
                        <input type="number" v-model.number="it.fat" step="0.1" @input="recalc(it, 'fat')" />
                      </div>
                    </div>
                  </div>
                </article>
              </TransitionGroup>
            </div>
          </section>

          <!-- 4. 사진 및 메모 -->
          <section class="form-section mt-10">
            <h3 class="section-title italic">4. PHOTO & MEMO</h3>
            <div class="extra-grid mt-8">
              <!-- 🌟 사진 크기에 맞춰지는 업로드 존 -->
              <div class="upload-zone" :class="{ 'has-preview': photoPreview }" @click="$refs.fileInput.click()">
                <input type="file" ref="fileInput" hidden @change="onFileChange" accept="image/*" />
                <div v-if="!photoPreview" class="upload-placeholder">
                  <div class="up-icon-box"><Camera :size="28" /></div>
                  <p class="font-black">식단 사진을 업로드하세요</p>
                  <span>분석 결과가 더 정밀해집니다</span>
                </div>
                <img v-else :src="photoPreview" class="preview-img" />
              </div>

              <div class="memo-zone mt-10">
                <label class="field-label">오늘의 식사 소감</label>
                <textarea class="premium-textarea" v-model="log.memo" placeholder="식사 분위기나 맛은 어땠나요?"></textarea>
              </div>
            </div>
          </section>

          <!-- 🌟 하단 액션 버튼 (원본 그라데이션 스타일 복구) -->
          <footer class="footer-actions mt-10">
            <div class="validation-hint mt-10" v-if="!canSave">
              <Info :size="16" />
              <span>날짜, 끼니, 음식을 모두 입력해야 완료할 수 있습니다.</span>
            </div>
            <div class="button-group">
              <button class="btn-cancel font-black" @click="goBack">취소하기</button>
              <button class="cta-submit font-black" :disabled="!canSave" @click="saveDiet">
                {{ log.dietId ? '수정 내용 저장' : '식단 기록 완료' }}
              </button>
            </div>
          </footer>
        </div>
      </div>
    </main>
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

.blob {
  position: fixed;
  border-radius: 50%;
  filter: blur(160px);
  z-index: 0;
  opacity: 0.2;
  pointer-events: none;
  transition: all 0.8s cubic-bezier(0.23, 1, 0.32, 1);
}
.blob-eat { top: -10%; left: -10%; width: 60vw; height: 60vw; background: radial-gradient(circle, rgba(163, 230, 53, 0.8) 0%, transparent 70%); } 
.blob-medi { bottom: -15%; right: -10%; width: 55vw; height: 55vw; background: radial-gradient(circle, rgba(56, 189, 248, 0.7) 0%, transparent 70%); }

.fade-blob-enter-active, .fade-blob-leave-active { transition: opacity 0.8s ease; }
.fade-blob-enter-from, .fade-blob-leave-to { opacity: 0; }

.content-wrapper {
  width: 100%;
  max-width: 920px;
  position: relative;
  z-index: 10;
}

.glass-card {
  background: rgba(255, 255, 255, 0.45);
  backdrop-filter: blur(30px);
  -webkit-backdrop-filter: blur(30px);
  border: 1px solid rgba(255, 255, 255, 0.8);
  border-radius: 50px;
  padding: 60px 50px;
  box-shadow: 0 40px 100px rgba(0, 0, 0, 0.02);
}

.page-header { text-align: center; margin-bottom: 50px; }
.badge {
  display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px;
  background: white; border-radius: 999px; margin-bottom: 24px;
  font-size: 0.65rem; font-weight: 850; color: #7c4dff; border: 1.5px solid #f1f5f9;
}
.hero-title { font-size: 3.5rem; line-height: 0.95; font-weight: 950; letter-spacing: -0.05em; margin-bottom: 20px; }

.gradient-text {
  background: linear-gradient(to bottom right, #1a1a1a, var(--point-color));
  -webkit-background-clip: text; background-clip: text; color: transparent;
  transition: all 0.5s ease;
}
.hero-subtext { font-size: 1.1rem; opacity: 0.6; font-weight: 500; }

.section-title {
  font-size: 1.1rem; font-weight: 900; letter-spacing: 0.1em;
  color: #1a1a1a; margin-bottom: 24px; display: flex; align-items: center; gap: 12px;
}
.section-title::after { content: ''; flex: 1; height: 1px; background: rgba(0,0,0,0.05); }

/* 입력 요소 */
.input-row { display: flex; gap: 20px; align-items: stretch; }
.date-picker-group { width: 280px; }
.meal-selection-group { flex: 1; }

.input-group label { display: block; font-size: 0.85rem; font-weight: 850; color: #1a1a1a; opacity: 0.6; margin-bottom: 12px; margin-left: 8px; }

.modern-input-box {
  background: white; border: 1.5px solid #f1f5f9;
  border-radius: 20px; padding: 14px 20px; display: flex; align-items: center; gap: 12px; transition: 0.3s;
  height: 56px;
}
.modern-input-box input { background: none; border: none; outline: none; width: 100%; font-weight: 800; color: #1a1a1a; font-size: 0.95rem; }
.modern-input-box:focus-within { border-color: var(--point-color); box-shadow: 0 10px 25px var(--point-soft); }
.input-icon { color: var(--point-color); }

.chip-group { display: flex; gap: 8px; height: 56px; align-items: center; }
.mode-chip {
  flex: 1; height: 100%; padding: 0; border-radius: 18px; border: 1.5px solid transparent;
  background: rgba(0, 0, 0, 0.04); font-size: 0.9rem; font-weight: 850;
  color: #94a3b8; cursor: pointer; transition: 0.3s;
}
.mode-chip.active { background: white; color: #1a1a1a; border-color: #1a1a1a; box-shadow: 0 8px 16px rgba(0,0,0,0.06); }

/* 검색창 */
.search-bar-modern {
  display: flex; align-items: center; gap: 14px; background: rgba(255, 255, 255, 0.8);
  border: 1.5px solid #f1f5f9; border-radius: 24px; padding: 8px 8px 8px 24px;
  transition: 0.3s;
}
.search-bar-modern:focus-within { border-color: var(--point-color); box-shadow: 0 15px 35px var(--point-soft); }
.search-bar-modern input { flex: 1; border: none; outline: none; background: none; font-weight: 700; font-size: 1rem; color: #1a1a1a; }
.search-icon-fixed { color: #cbd5e1; }

.search-action-btn {
  padding: 12px 28px; border-radius: 18px; border: none; background: #1a1a1a;
  cursor: pointer; transition: 0.3s;
}
.btn-search-label { color: #ffffff !important; font-weight: 900; font-size: 0.85rem; letter-spacing: 0.02em; }
.search-action-btn:hover:not(:disabled) { background: var(--point-color); transform: scale(1.02); }
.search-action-btn:hover .btn-search-label { color: #1a1a1a !important; }

.search-results-inline {
  margin-top: 16px; background: rgba(255,255,255,0.6); border-radius: 30px;
  padding: 24px; border: 1px dashed rgba(0, 0, 0, 0.1);
}
.results-header { font-size: 0.75rem; font-weight: 900; color: #1a1a1a; opacity: 0.3; margin-bottom: 16px; letter-spacing: 0.1em; }
.mini-result-list { display: flex; flex-direction: column; gap: 10px; max-height: 280px; overflow-y: auto; padding-right: 10px; }

/* 커스텀 스크롤바 */
.custom-scroll::-webkit-scrollbar { width: 5px; }
.custom-scroll::-webkit-scrollbar-track { background: transparent; }
.custom-scroll::-webkit-scrollbar-thumb { background: #e2e8f0; border-radius: 10px; }

.mini-item-inline {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 24px; border-radius: 20px; background: white; cursor: pointer; transition: 0.2s;
  box-shadow: 0 4px 12px rgba(0,0,0,0.02);
}
.mini-item-inline:hover { transform: translateX(8px); background: #f0fdf4; border-color: #a3e635; }
.mini-item-inline .name { display: block; font-weight: 850; font-size: 1rem; color: #1a1a1a; }
.mini-item-inline .corp { font-size: 0.75rem; color: #94a3b8; font-weight: 700; margin-top: 2px; }

.kcal-pill { 
  display: flex; align-items: baseline; gap: 3px; 
  background: #f8fafc; padding: 6px 14px; border-radius: 12px;
}
.kcal-val { font-size: 0.95rem; font-weight: 900; color: #1a1a1a; }
.kcal-unit { font-size: 0.65rem; font-weight: 800; color: #94a3b8; }

.direct-add-hint { margin-top: 16px; text-align: center; }
.hint-text { font-size: 0.85rem; font-weight: 700; color: #cbd5e1; margin-bottom: 8px; }
.btn-direct {
  background: none; border: 1.5px solid #a3e635; color: #a3e635; padding: 8px 24px;
  border-radius: 14px; font-weight: 850; font-size: 0.9rem; cursor: pointer; transition: 0.3s;
}
.btn-direct:hover { background: #a3e635; color: white; }

/* 🌟 검색 결과 유도 중앙 정렬 */
.direct-add-hint { 
  display: flex; 
  flex-direction: column; 
  align-items: center; 
  justify-content: center; 
}
.hint-text { font-size: 0.9rem; color: #94a3b8; letter-spacing: -0.01em; }
.btn-direct {
  background: none; border: 1.5px solid var(--point-color); color: var(--point-color); padding: 10px 24px;
  border-radius: 999px; font-size: 0.95rem; cursor: pointer; transition: 0.3s;
}
.btn-direct:hover { background: var(--point-color); color: white; transform: translateY(-2px); }

/* 선택된 음식 카드 */
.food-item-card {
  background: white; border-radius: 28px; padding: 28px; border: 1px solid #f1f5f9;
  box-shadow: 0 10px 25px rgba(0,0,0,0.02); transition: 0.3s;
}
.food-item-card:hover { transform: scale(1.01); border-color: var(--point-color); }
.food-title .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--point-color); }

.nutri-input input:focus { border-color: var(--point-color); background: white !important; }

/* 🌟 사진 크기에 맞춰지는 업로드 존 */
.upload-zone {
  border: 2px dashed #e2e8f0; border-radius: 35px; min-height: 240px; height: auto;
  display: flex; align-items: center; justify-content: center; cursor: pointer; transition: 0.4s; overflow: hidden;
  background: rgba(255,255,255,0.4);
}
.upload-zone:hover { border-color: var(--point-color); background: #f0fdf4; }
.upload-zone.has-preview { border-style: solid; border-color: #f1f5f9; }

.upload-placeholder { 
  display: flex; 
  flex-direction: column; 
  align-items: center; 
  justify-content: center; 
  text-align: center; 
  color: #94a3b8; 
}
.up-icon-box { margin-bottom: 14px; color: #cbd5e1; }
.upload-placeholder p { font-size: 1.05rem; color: #475569; margin-bottom: 6px; }
.upload-placeholder span { font-size: 0.85rem; opacity: 0.6; }
.preview-img { width: 100%; height: auto; max-height: 500px; object-fit: contain; }

/* 🌟 메모 작성창 스타일 고도화 */
.memo-zone { display: flex; flex-direction: column; }
.field-label { font-size: 0.85rem; font-weight: 850; color: #1a1a1a; opacity: 0.6; margin-bottom: 12px; margin-left: 8px; }
.premium-textarea {
  flex: 1; border: 1.5px solid #f1f5f9; background: white; border-radius: 24px;
  padding: 24px; font-weight: 600; font-size: 1.05rem; outline: none; resize: none; transition: 0.3s;
  min-height: 180px; line-height: 1.6; color: #1a1a1a;
}
.premium-textarea:focus { border-color: var(--point-color); box-shadow: 0 10px 30px var(--point-soft); transform: translateY(-2px); }

/* 🌟 버튼 스타일 (원본 스타일 복구 + 모드 동기화) */
.footer-actions { text-align: center; }
.button-group { display: flex; gap: 16px; }

.btn-cancel {
  flex: 1; padding: 18px; border-radius: 999px; border: 1.5px solid #1a1a1a;
  background: transparent; font-size: 1rem; cursor: pointer; transition: 0.3s;
}
.btn-cancel:hover { background: #f1f5f9; transform: translateY(-2px); }

.cta-submit {
  flex: 2; padding: 18px; border-radius: 999px; border: none;
  font-size: 1.1rem; color: white; cursor: pointer; transition: all 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275);
  background: linear-gradient(135deg, #1a1a1a 0%, #333 100%);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.1);
}

.cta-submit:hover:not(.disabled) { 
  background: linear-gradient(135deg, var(--point-color), #1a1a1a);
  transform: translateY(-5px); 
  box-shadow: 0 20px 40px var(--point-soft); 
}
.cta-submit.disabled { opacity: 0.3; cursor: not-allowed; transform: none; box-shadow: none; }

.validation-hint { display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 0.9rem; font-weight: 800; color: var(--point-color); }

.italic { font-style: italic; }
.font-black { font-weight: 950; }
.mt-10 { margin-top: 2.5rem; }
.mt-24 { margin-top: 6rem; }

@media (max-width: 800px) {
  .input-row, .extra-grid { flex-direction: column; }
  .glass-card { padding: 40px 24px; border-radius: 40px; }
  .hero-title { font-size: 2.8rem; }
}
</style>
