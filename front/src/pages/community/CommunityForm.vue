<script setup>
import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import communityApi from "@/api/community";
import { 
  Sparkles, 
  X, 
  Camera, 
  Save, 
  Loader2, 
  Info,
  Type,
  AlignLeft,
  Trash2
} from "lucide-vue-next";

const route = useRoute();
const router = useRouter();

const postId = route.params.id;
const mode = route.query.mode || "EAT";
const isEdit = !!postId;

/* =========================
   상태 관리 (멀티 이미지 대응)
========================= */
const title = ref("");
const content = ref("");
const files = ref([]); // 🌟 여러 파일을 담을 배열
const previewImages = ref([]); // 🌟 미리보기 이미지 객체 배열
const loading = ref(false);
const errorMsg = ref("");

// ✅ 버튼 활성 조건 (제목, 내용 필수)
const canSubmit = computed(() => {
  return title.value.trim() && content.value.trim();
});

// ✅ 비활성 이유 안내
const disabledHint = computed(() => {
  const missing = [];
  if (!title.value.trim()) missing.push("제목");
  if (!content.value.trim()) missing.push("내용");
  if (missing.length > 0) return `필수 항목 입력 필요: ${missing.join(", ")}`;
  return "";
});

// 수정일 경우 기존 내용 로딩
const loadPost = async () => {
  if (!isEdit) return;
  try {
    loading.value = true;
    errorMsg.value = "";
    const res = await communityApi.getDetail(postId);
    const p = res.data.post;
    title.value = p.title;
    content.value = p.content;
    
    // 기존 이미지가 있는 경우 (단일 이미지 기준에서 배열 확장 대응 가능하게 구조화)
    if (p.imagePath) {
      previewImages.value.push({
        url: `${import.meta.env.VITE_API_BASE_URL || ''}/uploads/${p.imagePath}`,
        isExisting: true
      });
    }
  } catch (e) {
    console.error("게시글 불러오기 실패", e);
    errorMsg.value = "기존 게시글 정보를 불러오지 못했습니다.";
  } finally {
    loading.value = false;
  }
};

// 🌟 멀티 파일 선택 핸들러
const onFileChange = (e) => {
  const selectedFiles = Array.from(e.target.files);
  
  selectedFiles.forEach(file => {
    // 실제 전송할 파일 배열에 추가
    files.value.push(file);
    
    // 미리보기 URL 생성
    const reader = new FileReader();
    reader.onload = (event) => {
      previewImages.value.push({
        url: event.target.result,
        isExisting: false,
        fileRef: file // 삭제 시 매칭용
      });
    };
    reader.readAsDataURL(file);
  });
};

// 🌟 선택한 이미지 개별 삭제
const removeImage = (index) => {
  const target = previewImages.value[index];
  if (!target.isExisting) {
    // 새로 추가한 파일인 경우 실제 전송 배열(files)에서도 제거
    files.value = files.value.filter(f => f !== target.fileRef);
  }
  previewImages.value.splice(index, 1);
};

// 게시글 저장/수정 제출
const handleSubmit = async () => {
  if (!canSubmit.value) return;

  const formData = new FormData();
  formData.append(
    "dto",
    new Blob(
      [
        JSON.stringify({
          title: title.value,
          content: content.value,
          modeType: mode,
        }),
      ],
      { type: "application/json" }
    )
  );

  // 🌟 여러 개의 파일을 formData에 추가
  if (files.value.length > 0) {
    files.value.forEach((file) => {
      formData.append("uploadFile", file); 
    });
  }

  try {
    loading.value = true;
    if (isEdit) {
      await communityApi.updateFormData(postId, formData);
      router.push(`/community/${postId}`);
    } else {
      await communityApi.writeFormData(formData);
      router.push("/community");
    }
  } catch (err) {
    console.error(err);
    errorMsg.value = "저장 중 오류가 발생했습니다.";
  } finally {
    loading.value = false;
  }
};

const goBack = () => router.back();

onMounted(loadPost);
</script>

<template>
  <div class="intro-container">
    <!-- 배경 장식 -->
    <div class="background-decorations">
      <div class="blob blob-eat"></div>
      <div class="blob blob-medi"></div>
    </div>

    <main class="content-wrapper">
      <div class="form-card glass-card">
        <!-- 헤더 섹션 -->
        <header class="page-header">
          <div class="badge-row">
            <div class="badge glass-effect">
              <Sparkles :size="12" class="text-primary" />
              <span class="font-black italic text-primary">COMMUNITY EDITOR</span>
            </div>
          </div>
          <h2 class="page-title font-black italic tracking-tighter">
            POST<br />
            <span class="gradient-text">{{ isEdit ? 'REVISION.' : 'WRITING.' }}</span>
          </h2>
          <p class="page-desc">{{ isEdit ? "기존 게시글의 내용을 수정하고 업데이트하세요." : "당신의 건강한 일상을 동료들과 공유해보세요." }}</p>
        </header>

        <!-- 폼 본문 -->
        <form v-if="!loading" @submit.prevent="handleSubmit" class="form-container">
          <div v-if="errorMsg" class="error-banner">{{ errorMsg }}</div>

          <!-- 1. 제목 및 내용 -->
          <section class="form-section">
            <h3 class="section-title italic">1. CONTENT INFO</h3>
            
            <div class="input-group">
              <label>제목 <span class="required">*</span></label>
              <div class="modern-input-box">
                <Type :size="18" class="input-icon" />
                <input type="text" v-model="title" placeholder="게시글 제목을 입력하세요" />
              </div>
            </div>

            <div class="input-group mt-10">
              <label>내용 <span class="required">*</span></label>
              <div class="modern-textarea-box">
                <AlignLeft :size="18" class="textarea-icon" />
                <textarea v-model="content" rows="8" placeholder="나누고 싶은 건강한 지식이나 질문을 적어주세요."></textarea>
              </div>
            </div>
          </section>

          <!-- 2. 이미지 첨부 (멀티 업로드 대응) -->
          <section class="form-section mt-16">
            <h3 class="section-title italic">2. MEDIA ATTACHMENT</h3>
            <div class="upload-wrapper mt-8">
              <div class="upload-zone" @click="$refs.fileInput.click()">
                <input 
                  type="file" 
                  ref="fileInput" 
                  hidden 
                  @change="onFileChange" 
                  accept="image/*" 
                  multiple 
                />
                <div class="upload-placeholder">
                  <div class="up-icon-box"><Camera :size="24" /></div>
                  <p class="font-black">이미지 업로드</p>
                  <span>여러 장의 사진을 선택하여 동시 업로드가 가능합니다.</span>
                </div>
              </div>

              <!-- 멀티 미리보기 영역 -->
              <div v-if="previewImages.length > 0" class="preview-grid mt-6">
                <TransitionGroup name="tag-pop">
                  <div v-for="(img, idx) in previewImages" :key="idx" class="preview-item animate-in">
                    <img :src="img.url" class="preview-img" />
                    <button type="button" class="btn-remove-mini" @click="removeImage(idx)">
                      <X :size="14" />
                    </button>
                  </div>
                </TransitionGroup>
              </div>
            </div>
          </section>

          <!-- 하단 액션 버튼 -->
          <footer class="footer-actions mt-16">
            <div v-if="!canSubmit" class="validation-hint mb-6">
              <Info :size="14" />
              <span>{{ disabledHint }}</span>
            </div>
            
            <div class="button-group">
              <button type="button" class="btn-cancel font-black" @click="goBack">취소하기</button>
              <button 
                type="submit" 
                class="main-start-btn" 
                :disabled="!canSubmit || loading"
              >
                <template v-if="loading">
                  <Loader2 :size="20" class="spinner" />
                  <span>PROCESSING...</span>
                </template>
                <template v-else>
                  <span class="btn-text-white">{{ isEdit ? "수정 완료하기" : "게시글 등록하기" }}</span>
                </template>
              </button>
            </div>
          </footer>
        </form>

        <!-- 로딩 상태 -->
        <div v-else class="loading-state">
          <Loader2 class="spinner-large" :size="48" />
          <p class="font-black italic opacity-30 mt-6">SYNCHRONIZING...</p>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
/* --- 🌟 인트로 배경 및 공통 레이아웃 --- */
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

.background-decorations {
  position: fixed; top: 0; left: 0; width: 100%; height: 100%; z-index: 0; pointer-events: none;
}

.blob {
  position: fixed;
  border-radius: 50%;
  filter: blur(160px);
  z-index: 0;
  opacity: 0.25;
  pointer-events: none;
}
.blob-eat { 
  top: -10%; left: -10%; width: 55vw; height: 55vw; 
  background: radial-gradient(circle, rgba(163, 230, 53, 0.8) 0%, transparent 70%); 
} 
.blob-medi { 
  bottom: -15%; right: -10%; width: 50vw; height: 50vw; 
  background: radial-gradient(circle, rgba(56, 189, 248, 0.7) 0%, transparent 70%); 
}

.content-wrapper { position: relative; z-index: 10; max-width: 820px; margin: 0 auto; width: 100%; }

/* --- 🌟 Glassmorphism 카드 --- */
.glass-card {
  background: rgba(255, 255, 255, 0.4);
  backdrop-filter: blur(30px);
  -webkit-backdrop-filter: blur(30px);
  border: 1px solid rgba(255, 255, 255, 0.6);
  border-radius: 40px;
  padding: 60px 48px;
  box-shadow: 0 40px 100px rgba(0, 0, 0, 0.02);
}

/* --- 🌟 헤더 --- */
.page-header { text-align: center; margin-bottom: 60px; }
.badge-row { display: flex; justify-content: center; margin-bottom: 24px; }
.badge { display: inline-flex; align-items: center; gap: 8px; padding: 8px 16px; font-size: 0.65rem; border-radius: 999px; }

.page-title { font-size: 3.5rem; line-height: 1; margin-bottom: 16px; }
.gradient-text {
  background: linear-gradient(to right, #1A1A1A, #7C4DFF, #FF8A65);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.page-desc { font-size: 1.1rem; opacity: 0.4; font-weight: 600; letter-spacing: -0.01em; }

/* --- 🌟 섹션 타이틀 --- */
.section-title {
  font-size: 1.1rem; font-weight: 900; letter-spacing: 0.05em;
  color: #1a1a1a; margin-bottom: 24px; display: flex; align-items: center; gap: 12px;
}
.section-title::after { content: ''; flex: 1; height: 1px; background: rgba(0,0,0,0.05); }

/* --- 🌟 입력 요소 --- */
.input-group label {
  display: block; font-size: 0.85rem; font-weight: 850; color: #1a1a1a;
  opacity: 0.6; margin-bottom: 12px; margin-left: 8px;
}
.required { color: #e11d48; margin-left: 4px; }

.modern-input-box {
  background: white; border: 1.5px solid #f1f5f9;
  border-radius: 20px; padding: 14px 20px; display: flex; align-items: center; gap: 12px; transition: 0.3s;
}
.modern-input-box input {
  background: none; border: none; outline: none; width: 100%; font-weight: 800; color: #1a1a1a; font-size: 1rem;
}
.modern-input-box:focus-within {
  border-color: #7C4DFF; box-shadow: 0 10px 25px rgba(124, 77, 255, 0.08);
}

.modern-textarea-box {
  background: white; border: 1.5px solid #f1f5f9;
  border-radius: 24px; padding: 24px; display: flex; gap: 16px; transition: 0.3s;
}
.modern-textarea-box textarea {
  flex: 1; background: none; border: none; outline: none; font-weight: 600;
  font-size: 1rem; color: #1a1a1a; line-height: 1.6; resize: vertical;
}
.modern-textarea-box:focus-within {
  border-color: #7C4DFF; box-shadow: 0 10px 25px rgba(124, 77, 255, 0.08);
}
.input-icon, .textarea-icon { color: #cbd5e1; }

/* --- 🌟 업로드 및 멀티 미리보기 --- */
.upload-zone {
  border: 2px dashed #e2e8f0; border-radius: 35px; height: 160px;
  display: flex; align-items: center; justify-content: center; cursor: pointer; transition: 0.3s;
  background: rgba(255,255,255,0.4);
}
.upload-zone:hover { border-color: #7C4DFF; background: #f5f7ff; }
.upload-placeholder { text-align: center; color: #94a3b8; }
.up-icon-box { margin-bottom: 12px; color: #cbd5e1; }
.upload-placeholder p { font-size: 1rem; color: #475569; margin-bottom: 6px; }
.upload-placeholder span { font-size: 0.8rem; opacity: 0.6; }

.preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 16px;
}
.preview-item {
  position: relative;
  aspect-ratio: 1/1;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 8px 16px rgba(0,0,0,0.1);
  border: 2px solid white;
}
.preview-img { width: 100%; height: 100%; object-fit: cover; }
.btn-remove-mini {
  position: absolute; top: 6px; right: 6px; width: 24px; height: 24px;
  border-radius: 50%; background: rgba(0,0,0,0.6); color: white; border: none;
  cursor: pointer; display: flex; align-items: center; justify-content: center; transition: 0.2s;
}
.btn-remove-mini:hover { background: #e11d48; transform: scale(1.1); }

/* --- 🌟 버튼 그룹 및 흰색 글씨 수정 --- */
.button-group { display: flex; gap: 16px; }
.btn-cancel {
  flex: 1; padding: 22px; border-radius: 999px; border: 1.5px solid #1a1a1a;
  background: none; font-weight: 850; font-size: 1rem; cursor: pointer; transition: 0.3s;
}
.btn-cancel:hover { background: #f1f5f9; }

.main-start-btn {
  flex: 2; display: flex; align-items: center; justify-content: center;
  padding: 22px; background: #1a1a1a; border: none; border-radius: 999px;
  cursor: pointer; transition: all 0.4s;
}

/* 🌟 마우스 올리기 전에도 흰색이 확실히 보이도록 명시적 수정 */
.btn-text-white {
  color: #ffffff !important;
  font-weight: 900;
  font-size: 1.1rem;
}

/* 아이콘 색상도 흰색으로 고정 */
.text-white { color: #ffffff !important; }

.main-start-btn:hover:not(:disabled) { 
  background: #7C4DFF; 
  transform: translateY(-5px); 
  box-shadow: 0 20px 40px rgba(124, 77, 255, 0.3); 
}
.main-start-btn:disabled { opacity: 0.2; cursor: not-allowed; }

.validation-hint { display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 0.85rem; font-weight: 800; color: #7C4DFF; opacity: 0.6; }

/* --- 🌟 기타 상태 --- */
.loading-state { text-align: center; padding: 100px 0; }
.spinner-large { animation: rotate 1.5s linear infinite; color: #7C4DFF; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

.error-banner { background: #fff1f2; color: #e11d48; padding: 16px; border-radius: 16px; margin-bottom: 24px; font-weight: 700; text-align: center; }

/* Utilities */
.font-black { font-weight: 950; }
.italic { font-style: italic; }
.tracking-tighter { letter-spacing: -0.06em; }
.mt-10 { margin-top: 2.5rem; }
.mt-16 { margin-top: 4rem; }
.ml-2 { margin-left: 0.5rem; }

.animate-in { animation: slideUp 0.6s cubic-bezier(0.23, 1, 0.32, 1) forwards; }
@keyframes slideUp { from { opacity: 0; transform: translateY(20px); } to { opacity: 1; transform: translateY(0); } }

/* 애니메이션 */
.tag-pop-enter-active { animation: tagPop 0.4s cubic-bezier(0.23, 1, 0.32, 1); }
.tag-pop-leave-active { animation: tagPop 0.3s reverse ease-in; }
@keyframes tagPop {
  0% { opacity: 0; transform: scale(0.5) translateY(10px); }
  100% { opacity: 1; transform: scale(1) translateY(0); }
}

@media (max-width: 640px) {
  .glass-card { padding: 40px 24px; border-radius: 40px; }
  .page-title { font-size: 2.8rem; }
  .button-group { flex-direction: column; }
}
</style>
