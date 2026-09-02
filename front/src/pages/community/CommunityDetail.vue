<script setup>
import { ref, onMounted, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import communityApi from "@/api/community";
import { 
  Sparkles, 
  ArrowLeft, 
  User, 
  Calendar, 
  Heart, 
  Bookmark, 
  MessageSquare, 
  Edit3, 
  Trash2, 
  X, 
  Send,
  MoreHorizontal,
  ChevronRight,
  ArrowRight,
  Loader2,
  Check,
  Inbox
} from "lucide-vue-next";

const route = useRoute();
const router = useRouter();

const postId = route.params.id;

/* =========================
   상태 관리
========================= */
const post = ref(null);
const comments = ref([]);
const loginUserId = ref(null);
const isWriter = ref(false);
const loading = ref(true);

// 댓글 수정 상태
const editingId = ref(null);
const editContent = ref("");
const newComment = ref("");

// 디자인 시스템 컬러
const designSystem = {
  primary: "#7C4DFF",
  secondary: "#FF8A65",
  text: "#1A1A1A"
};

// 상세조회
const loadDetail = async () => {
  loading.value = true;
  try {
    const res = await communityApi.getDetail(postId);
    post.value = res.data.post;
    comments.value = res.data.comments;
    loginUserId.value = res.data.loginUserId;
    isWriter.value = post.value.userId === loginUserId.value;
  } catch (e) {
    console.error("게시글 로드 실패", e);
  } finally {
    loading.value = false;
  }
};

const goList = () => router.push("/community");
const goEdit = () => router.push(`/community/edit/${postId}`);

const deletePost = async () => {
  if (!confirm("정말 이 게시글을 삭제할까요?")) return;
  await communityApi.delete(postId);
  router.push("/community");
};

// 좋아요/스크랩
const toggleLike = async () => {
  await communityApi.toggleLike(postId);
  await loadDetail();
};
const toggleScrap = async () => {
  await communityApi.toggleScrap(postId);
  await loadDetail();
};

// 댓글 액션
const addComment = async () => {
  if (!newComment.value.trim()) return;
  await communityApi.addComment(postId, newComment.value);
  newComment.value = "";
  await loadDetail();
};

const deleteComment = async (commentId) => {
  if (!confirm("댓글을 삭제할까요?")) return;
  await communityApi.deleteComment(commentId);
  await loadDetail();
};

const startEdit = (c) => {
  editingId.value = c.commentId;
  editContent.value = c.content;
};

const cancelEdit = () => {
  editingId.value = null;
};

const saveComment = async () => {
  if (!editContent.value.trim()) return;
  await communityApi.updateComment(editingId.value, editContent.value);
  editingId.value = null;
  await loadDetail();
};

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '';
const fullImageUrl = computed(() => {
  if (!post.value?.imagePath) return null;
  return `${apiBaseUrl}/uploads/${post.value.imagePath}`;
});

const formatDate = (dateStr) => {
  if (!dateStr) return "";
  const date = new Date(dateStr);
  return `${date.getFullYear()}. ${date.getMonth() + 1}. ${date.getDate()}`;
};

onMounted(loadDetail);
</script>

<template>
  <div class="page-container">
    <!-- 🌟 수정됨: 가이드라인 및 MyScrap 배경 완벽 동기화 -->
    <div class="background-decorations">
      <div class="blob blob-eat"></div>
      <div class="blob blob-medi"></div>
    </div>

    <main class="content-wrapper" v-if="post">
      <!-- 🌟 인트로 스타일 HUD 헤더 -->
      <header class="page-header">
        <div class="header-top-row">
          <button class="back-btn glass-effect" @click="goList">
            <ArrowLeft :size="16" />
            <span class="font-black italic">LIST</span>
          </button>
        </div>

        <div class="title-area-centered mt-10">
          <div class="badge-row-centered">
            <div class="badge glass-effect">
              <Sparkles :size="12" class="text-primary" />
              <span class="font-black italic text-primary">POST VIEW.</span>
            </div>
          </div>
          <h2 class="page-title font-black italic tracking-tighter centered">
            THE 
            <span class="gradient-text">INSIGHT.</span>
          </h2>
        </div>
      </header>

      <!-- 🌟 메인 콘텐츠 카드 -->
      <article class="post-detail-card glass-effect animate-in">
        <div class="card-inner-glow"></div>
        
        <header class="card-head">
          <div class="post-main-info">
            <h1 class="post-title font-black italic tracking-tighter">{{ post.title }}</h1>
            <div class="post-meta">
              <div class="author-tag font-black italic text-primary">
                <User :size="14" /> @{{ post.writerId.toUpperCase() }}
              </div>
              <div class="date-text font-black opacity-30">
                <Calendar :size="14" /> {{ formatDate(post.createdAt) }}
              </div>
            </div>
          </div>
          
          <!-- 수정/삭제 버튼 -->
          <div v-if="isWriter" class="post-actions">
            <button class="action-btn edit" @click="goEdit" title="수정">
              <Edit3 :size="18" />
            </button>
            <button class="action-btn delete" @click="deletePost" title="삭제">
              <Trash2 :size="18" />
            </button>
          </div>
        </header>

        <!-- 본문 텍스트 -->
        <section class="post-body">
          <div class="post-content-text" v-html="post.content"></div>
          
          <div v-if="post.imagePath" class="post-image-box mt-10">
            <img :src="fullImageUrl" alt="Post Image" class="main-img" />
          </div>
        </section>

        <!-- 하단 반응 영역 -->
        <footer class="post-footer mt-16">
          <div class="reaction-group">
            <button class="reaction-btn-compact like-btn glass-effect" @click="toggleLike">
              <Heart :size="16" :class="{ 'fill-peach': post.likeCount > 0 }" class="text-peach" />
              <span class="font-black">{{ post.likeCount }}</span>
            </button>
            <button class="reaction-btn-compact scrap-btn glass-effect" @click="toggleScrap">
              <Bookmark :size="16" :class="{ 'fill-primary': post.scrapCount > 0 }" class="text-primary" />
              <span class="font-black">{{ post.scrapCount }}</span>
            </button>
          </div>
          
          <div class="share-hint-refined font-black italic">
            SHARED BY MEDEAT COMMUNITY
          </div>
        </footer>
      </article>

      <!-- 🌟 댓글 섹션 -->
      <section class="comment-section mt-12 animate-in" style="animation-delay: 0.1s">
        <div class="section-header-row mb-6">
          <h3 class="section-title font-black italic">COMMENTS.</h3>
          <span class="comment-count-badge font-black">{{ comments.length }}</span>
        </div>

        <!-- 댓글 리스트 -->
        <div class="comment-list">
          <div v-for="c in comments" :key="c.commentId" class="comment-row glass-effect">
            <div v-if="editingId !== c.commentId" class="comment-view">
              <div class="comment-head">
                <div class="comment-author font-black italic text-primary">
                  <User :size="12" /> @{{ c.writerId.toUpperCase() }}
                </div>
                <div class="comment-date font-black opacity-30">{{ formatDate(c.createdAt) }}</div>
              </div>
              <p class="comment-content-text font-black">{{ c.content }}</p>
              <div v-if="loginUserId === c.userId || isWriter" class="comment-actions">
                <button class="c-action-btn" @click="startEdit(c)">수정</button>
                <button class="c-action-btn delete" @click="deleteComment(c.commentId)">삭제</button>
              </div>
            </div>

            <!-- 댓글 수정 모드 -->
            <div v-else class="comment-edit-mode">
              <input v-model="editContent" type="text" class="edit-input glass-effect" />
              <div class="edit-actions">
                <button class="edit-save-btn font-black" @click="saveComment">저장</button>
                <button class="edit-cancel-btn font-black" @click="cancelEdit">취소</button>
              </div>
            </div>
          </div>
          
          <!-- 코멘트 없을 때 디자인 -->
          <div v-if="comments.length === 0" class="empty-comments-centered glass-effect">
            <div class="empty-visual">
              <Inbox :size="32" class="opacity-20" />
            </div>
            <p class="font-black italic opacity-30">첫 번째 댓글을 남겨 동기를 부여해주세요.</p>
          </div>
        </div>

        <!-- 댓글 작성 입력창 -->
        <div class="comment-input-area glass-effect mt-16">
          <div class="input-wrapper">
            <input 
              v-model="newComment" 
              type="text" 
              placeholder="따뜻한 댓글을 남겨주세요..." 
              @keyup.enter="addComment"
            />
            <button class="comment-submit-btn" @click="addComment" :disabled="!newComment.trim()">
              <Send :size="18" />
            </button>
          </div>
        </div>
      </section>

      <div class="page-bottom-spacer mt-20"></div>
    </main>

    <!-- 로딩 상태 -->
    <div v-else class="state-container">
      <Loader2 class="spinner" :size="48" />
      <p class="font-black italic opacity-30">SYNCING INSIGHT...</p>
    </div>
  </div>
</template>

<style scoped>
/* --- 🌟 가이드라인 및 MyScrap 배경 완벽 동기화 --- */
.page-container {
  position: relative;
  min-height: 100vh;
  background-color: #f8f9fc;
  color: #1a1a1a;
  overflow-x: hidden;
  padding: 100px 24px 80px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}

.background-decorations {
  position: fixed; top: 0; left: 0; width: 100%; height: 100%; z-index: 0; pointer-events: none;
}

/* 배경 Blobs (MyScrap과 동일 수치) */
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

.content-wrapper { position: relative; z-index: 10; max-width: 860px; margin: 0 auto; width: 100%; }

/* --- 🌟 디자인 시스템: Glassmorphism (blur 20px 가이드 준수) --- */
.glass-effect {
  background: rgba(255, 255, 255, 0.45);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.6);
  border-radius: 32px;
  box-shadow: 0 8px 32px 0 rgba(31, 38, 135, 0.07);
}

/* --- 🌟 HUD Header --- */
.page-header { margin-bottom: 60px; width: 100%; }
.header-top-row { display: flex; justify-content: flex-start; align-items: center; }

.back-btn {
  display: flex; align-items: center; gap: 10px; padding: 10px 20px; border-radius: 999px;
  cursor: pointer; transition: 0.3s;
}
.back-btn:hover { background: white; transform: translateX(-5px); }

/* 중앙 정렬 스타일 */
.title-area-centered { text-align: center; }
.badge-row-centered { display: flex; justify-content: center; margin-bottom: 24px; }
.badge { display: inline-flex; align-items: center; gap: 8px; padding: 8px 16px; font-size: 0.65rem; border-radius: 999px; }

.page-title { font-size: 3.5rem; line-height: 1; margin-bottom: 16px; }
.page-title.centered { text-align: center; }
.gradient-text {
  background: linear-gradient(to right, #1A1A1A, #7C4DFF, #FF8A65);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.page-desc { font-size: 1.1rem; opacity: 0.4; font-weight: 600; letter-spacing: -0.01em; }

/* --- 🌟 게시글 상세 카드 --- */
.post-detail-card {
  padding: 60px; position: relative; overflow: hidden;
}
.card-inner-glow {
  position: absolute; top: -10%; left: -10%; width: 300px; height: 300px;
  background: radial-gradient(circle, rgba(124, 77, 255, 0.05) 0%, transparent 70%);
  pointer-events: none;
}

.card-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 48px; }
.post-title { font-size: 2rem; line-height: 1.2; margin-bottom: 16px; color: #1A1A1A; }
.post-meta { display: flex; gap: 24px; align-items: center; }
.author-tag { display: flex; align-items: center; gap: 8px; font-size: 0.85rem; letter-spacing: 0.05em; }
.date-text { display: flex; align-items: center; gap: 8px; font-size: 0.85rem; }

.post-actions { display: flex; gap: 12px; }
.action-btn {
  width: 44px; height: 44px; border-radius: 12px; border: none; background: #f1f5f9; color: #94a3b8;
  display: flex; align-items: center; justify-content: center; cursor: pointer; transition: 0.3s;
}
.action-btn.edit:hover { background: #1A1A1A; color: white; transform: rotate(-15deg); }
.action-btn.delete:hover { background: #FF8A65; color: white; transform: rotate(15deg); }

.post-body { min-height: 200px; }
.post-content-text { font-size: 1.15rem; line-height: 1.8; color: #475569; word-break: break-all; }

.post-image-box { border-radius: 32px; overflow: hidden; box-shadow: 0 20px 50px rgba(0,0,0,0.05); }
.main-img { width: 100%; height: auto; display: block; }

.post-footer { 
  display: flex; flex-direction: column; gap: 24px; padding-top: 40px; 
  border-top: 1px solid rgba(0,0,0,0.03); 
}
.reaction-group { display: flex; align-items: center; gap: 12px; }

.reaction-btn-compact {
  padding: 10px 20px; border-radius: 999px; display: flex; align-items: center; gap: 8px;
  cursor: pointer; transition: 0.3s cubic-bezier(0.175, 0.885, 0.32, 1.275);
  font-size: 0.95rem;
}
.reaction-btn-compact:hover { transform: translateY(-2px); background: white; }

.fill-peach { fill: #FF8A65; }
.fill-primary { fill: #7C4DFF; }

.share-hint-refined {
  font-size: 0.75rem; color: #94a3b8; opacity: 0.5; letter-spacing: 0.05em; text-align: center;
}

/* --- 🌟 댓글 섹션 --- */
.section-title { font-size: 1.1rem; letter-spacing: 0.1em; opacity: 0.3; }
.comment-count-badge { background: #1A1A1A; color: white; padding: 4px 12px; border-radius: 10px; font-size: 0.8rem; }
.section-header-row { display: flex; align-items: center; gap: 12px; }

.comment-row { padding: 24px; margin-bottom: 12px; transition: 0.3s; }
.comment-row:hover { background: rgba(255,255,255,0.6); }

.comment-head { display: flex; justify-content: space-between; margin-bottom: 10px; }
.comment-author { font-size: 0.75rem; display: flex; align-items: center; gap: 6px; }
.comment-date { font-size: 0.75rem; }
.comment-content-text { font-size: 1rem; color: #334155; line-height: 1.5; margin-bottom: 12px; }

.comment-actions { display: flex; gap: 12px; justify-content: flex-end; }
.c-action-btn { background: none; border: none; font-size: 0.75rem; font-weight: 850; color: #94a3b8; cursor: pointer; transition: 0.2s; }
.c-action-btn:hover { color: #1A1A1A; }
.c-action-btn.delete:hover { color: #FF8A65; }

/* 빈 댓글 디자인 */
.empty-comments-centered { 
  padding: 60px 40px; text-align: center; 
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 16px;
}
.empty-visual { margin-bottom: 4px; }

/* 댓글 수정 모드 */
.comment-edit-mode { display: flex; flex-direction: column; gap: 12px; }
.edit-input { width: 100%; padding: 14px 20px; font-weight: 800; color: #1A1A1A; border: 1.5px solid #7C4DFF !important; }
.edit-actions { display: flex; gap: 10px; justify-content: flex-end; }
.edit-save-btn { background: #1A1A1A; color: white; border: none; padding: 8px 16px; border-radius: 10px; cursor: pointer; }
.edit-cancel-btn { background: #f1f5f9; color: #94a3b8; border: none; padding: 8px 16px; border-radius: 10px; cursor: pointer; }

/* 댓글 입력 */
.comment-input-area { padding: 8px 8px 8px 24px; border-radius: 999px; }
.input-wrapper { display: flex; align-items: center; gap: 16px; }
.input-wrapper input {
  flex: 1; background: none; border: none; outline: none; font-weight: 800; font-size: 1rem; color: #1A1A1A;
}
.comment-submit-btn {
  width: 48px; height: 48px; border-radius: 50%; border: none; background: #1A1A1A; color: white;
  display: flex; align-items: center; justify-content: center; cursor: pointer; transition: 0.3s;
}
.comment-submit-btn:hover:not(:disabled) { background: #7C4DFF; transform: rotate(-20deg) scale(1.1); }
.comment-submit-btn:disabled { opacity: 0.2; cursor: not-allowed; }

/* --- 🌟 기타 상태 --- */
.state-container { padding: 120px 40px; text-align: center; }
.spinner { animation: rotate 1.5s linear infinite; color: #7C4DFF; margin: 0 auto 24px; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

/* Utilities */
.font-black { font-weight: 950; }
.italic { font-style: italic; }
.tracking-tighter { letter-spacing: -0.06em; }
.text-primary { color: #7C4DFF; }
.text-peach { color: #FF8A65; }
.mt-10 { margin-top: 2.5rem; }
.mt-12 { margin-top: 3rem; }
.mt-16 { margin-top: 4rem; }
.mt-20 { margin-top: 6rem; }
.mb-6 { margin-bottom: 1.5rem; }
.ml-auto { margin-left: auto; }
.ml-2 { margin-left: 0.5rem; }

.animate-in { animation: slideUp 0.8s cubic-bezier(0.23, 1, 0.32, 1) forwards; }
@keyframes slideUp { from { opacity: 0; transform: translateY(30px); } to { opacity: 1; transform: translateY(0); } }

@media (max-width: 768px) {
  .page-title { font-size: 2.8rem; }
  .post-detail-card { padding: 40px 24px; }
  .post-title { font-size: 1.6rem; }
  .reaction-btn-compact { padding: 8px 16px; font-size: 0.85rem; }
}
</style>
