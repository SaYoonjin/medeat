<script setup>
import { ref, onMounted, nextTick, onUnmounted, computed } from "vue";
import { useRoute } from "vue-router";
import chatApi from "@/api/chat";
import { Client } from "@stomp/stompjs";
import api from "@/api/axios";
import { 
  Sparkles, 
  Send, 
  User, 
  Wifi, 
  WifiOff, 
  X,
  MessageSquare,
  ChevronLeft,
  Circle
} from "lucide-vue-next";

// ===== Route =====
const route = useRoute();
const challengeId = Number(route.params.id);
const connected = ref(false);
const myUserId = ref(null);

// 🌟 현재 모드 감지 (배경 테마용)
const currentMode = computed(() => route.query.mode || "EAT");

// ===== State =====
const messages = ref([]);
const message = ref("");
const stompClient = ref(null);
const chatBox = ref(null);

// ===== Utils =====
const loadMyInfo = async () => {
  const res = await api.get("/auth/checkLogin");
  myUserId.value = res.data.userId;
};

const isMine = (msg) => {
  if (!myUserId.value) return false;
  return msg.userId === myUserId.value;
};

const scrollToBottom = async () => {
  await nextTick();
  if (chatBox.value) {
    chatBox.value.scrollTop = chatBox.value.scrollHeight;
  }
};

const loadMessages = async () => {
  const res = await chatApi.getMessages(challengeId);
  messages.value = res.data;
  scrollToBottom();
};

// ===== WebSocket 연결 =====
const connect = () => {
  stompClient.value = new Client({
    brokerURL: `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws-chat`,
    reconnectDelay: 5000,
    connectHeaders: {
      userId: String(myUserId.value),
    },
    debug: (str) => console.log("STOMP:", str),
    onConnect: () => {
      connected.value = true;
      stompClient.value.subscribe(
        `/sub/chat/room/${challengeId}`,
        (frame) => {
          messages.value.push(JSON.parse(frame.body));
          scrollToBottom();
        }
      );
    },
  });
  stompClient.value.activate();
};

// ===== 메시지 전송 =====
const send = () => {
  if (!message.value.trim()) return;
  if (!stompClient.value?.connected) return;
  if (!myUserId.value) return;

  stompClient.value.publish({
    destination: "/pub/chat/send",
    body: JSON.stringify({
      challengeId,
      userId: myUserId.value,
      content: message.value,
    }),
  });
  message.value = "";
};

const formatTime = (time) => {
  if (!time) return "";
  const d = new Date(time);
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
};

onUnmounted(() => {
  if (stompClient.value) {
    stompClient.value.deactivate();
  }
});

// ===== Lifecycle =====
onMounted(async () => {
  await loadMyInfo();
  await loadMessages();
  connect();
});
</script>

<template>
  <div class="page-container">
    <!-- 🌟 모드별 다이내믹 배경 블롭 (EAT: 연두 / MEDI_EAT: 하늘) -->
    <div class="background-decorations">
      <div v-if="currentMode === 'EAT'" class="blob blob-eat"></div>
      <div v-if="currentMode === 'MEDI_EAT'" class="blob blob-medi"></div>
      <div class="blob blob-bottom-fixed"></div>
    </div>

    <main class="content-wrapper">
      <!-- 🌟 인트로 스타일 HUD 헤더 -->
      <header class="page-header">
        <div class="header-top-row">
          <div class="badge glass-effect">
            <MessageSquare :size="12" class="text-primary" />
            <span class="font-black italic text-primary">LIVE CHANNELS</span>
          </div>
          <div class="active-indicator glass-effect" :class="{ off: !connected }">
            <div class="pulse-dot"></div>
            <span class="font-black italic">{{ connected ? 'SYNCED' : 'OFFLINE' }}</span>
          </div>
        </div>
        <h2 class="page-title font-black italic tracking-tighter">
          CHALLENGE<br />
          <span class="gradient-text">CHAT.</span>
        </h2>
        <p class="page-desc">함께 도전하는 동료들과 실시간으로 에너지를 나누세요.</p>
      </header>

      <!-- 🌟 글래스모피즘 채팅 카드 -->
      <div class="chat-card glass-effect">
        <div class="chat-inner-glow"></div>
        
        <!-- 채팅 메시지 영역 -->
        <div class="chat-box custom-scroll" ref="chatBox">
          <div
            v-for="(msg, idx) in messages"
            :key="idx"
            :class="['message-row', isMine(msg) ? 'mine' : 'other']"
          >
            <!-- 상대방 메시지 -->
            <template v-if="!isMine(msg)">
              <div class="profile-wrapper">
                <div class="profile-avatar glass-effect">
                  <User :size="18" class="opacity-40" />
                </div>
              </div>
              <div class="message-content">
                <div class="nickname font-black italic">@{{ msg.nickname?.toUpperCase() || 'MEMBER' }}</div>
                <div class="bubble-wrapper">
                  <div class="bubble glass-effect">{{ msg.content }}</div>
                  <span class="time font-black">{{ formatTime(msg.createdAt) }}</span>
                </div>
              </div>
            </template>

            <!-- 내 메시지 -->
            <template v-else>
              <div class="message-content">
                <div class="bubble-wrapper">
                  <span class="time font-black">{{ formatTime(msg.createdAt) }}</span>
                  <div class="bubble my-bubble font-black">{{ msg.content }}</div>
                </div>
              </div>
            </template>
          </div>
          
          <!-- 메시지가 없을 때 -->
          <div v-if="messages.length === 0" class="empty-chat-state">
            <Sparkles :size="32" class="text-primary opacity-20 mb-4" />
            <p class="font-black italic opacity-30">대화를 시작해보세요.</p>
          </div>
        </div>

        <!-- 하단 메시지 입력부 -->
        <footer class="chat-input-area">
          <div class="input-wrapper glass-effect">
            <input
              v-model="message"
              placeholder="Type your message..."
              @keydown.enter.prevent="send"
            />
            <button 
              class="send-btn" 
              @click="send" 
              :disabled="!message.trim() || !connected"
              :class="{ active: message.trim() }"
            >
              <Send :size="18" />
            </button>
          </div>
        </footer>
      </div>

      <div class="page-bottom-hint mt-10">
        <p class="font-black italic opacity-30">ENCRYPTED REAL-TIME CONNECTION ACTIVE</p>
      </div>
    </main>
  </div>
</template>

<style scoped>
/* --- 🌟 인트로 배경 및 공통 레이아웃 --- */
.page-container {
  position: relative;
  min-height: 100vh;
  background-color: #f8f9fc;
  color: #1a1a1a;
  overflow-x: hidden;
  padding: 100px 24px 60px;
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
  top: -10%; left: -10%; width: 60vw; height: 60vw; 
  background: radial-gradient(circle, rgba(163, 230, 53, 0.8) 0%, transparent 70%); 
} 
.blob-medi { 
  top: -10%; right: -10%; width: 60vw; height: 60vw; 
  background: radial-gradient(circle, rgba(56, 189, 248, 0.7) 0%, transparent 70%); 
}
.blob-bottom-fixed {
  bottom: -15%; left: 20%; width: 50vw; height: 50vw;
  background: radial-gradient(circle, rgba(255, 138, 101, 0.1) 0%, transparent 70%);
}

.content-wrapper { position: relative; z-index: 10; max-width: 600px; margin: 0 auto; width: 100%; }

/* --- 🌟 Glassmorphism 디자인 시스템 --- */
.glass-effect {
  background: rgba(255, 255, 255, 0.45);
  backdrop-filter: blur(25px);
  -webkit-backdrop-filter: blur(25px);
  border: 1px solid rgba(255, 255, 255, 0.6);
  border-radius: 32px;
  box-shadow: 0 8px 32px 0 rgba(31, 38, 135, 0.07);
}

/* --- 🌟 HUD Header --- */
.page-header { text-align: center; margin-bottom: 50px; }
.header-top-row { display: flex; justify-content: center; align-items: center; gap: 12px; margin-bottom: 24px; }

.badge { display: inline-flex; align-items: center; gap: 8px; padding: 8px 16px; font-size: 0.65rem; border-radius: 999px; }

.active-indicator { 
  display: flex; align-items: center; gap: 8px; padding: 8px 16px; border-radius: 999px;
  font-size: 0.65rem; color: #10b981;
}
.active-indicator.off { color: #94a3b8; }
.pulse-dot { width: 6px; height: 6px; background: currentColor; border-radius: 50%; position: relative; }
.active-indicator:not(.off) .pulse-dot::after {
  content: ""; position: absolute; inset: -4px; border-radius: 50%; background: currentColor; opacity: 0.3; animation: pulse 2s infinite;
}
@keyframes pulse { 0% { transform: scale(1); opacity: 0.3; } 100% { transform: scale(2.5); opacity: 0; } }

.page-title { font-size: 3.5rem; line-height: 1; margin-bottom: 16px; }
.gradient-text {
  background: linear-gradient(to right, #1A1A1A, #7C4DFF, #FF8A65);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.page-desc { font-size: 1.1rem; opacity: 0.4; font-weight: 600; letter-spacing: -0.01em; line-height: 1.4; }

/* --- 🌟 채팅 카드 --- */
.chat-card {
  height: 60vh; min-height: 500px; display: flex; flex-direction: column; position: relative; overflow: hidden;
}
.chat-inner-glow {
  position: absolute; top: -10%; right: -10%; width: 200px; height: 200px;
  background: radial-gradient(circle, rgba(124, 77, 255, 0.05) 0%, transparent 70%);
  pointer-events: none;
}

.chat-box { flex: 1; overflow-y: auto; padding: 30px; display: flex; flex-direction: column; gap: 20px; }

/* 스크롤바 */
.custom-scroll::-webkit-scrollbar { width: 4px; }
.custom-scroll::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.05); border-radius: 10px; }

.message-row { display: flex; max-width: 85%; animation: slideUp 0.4s cubic-bezier(0.23, 1, 0.32, 1) forwards; }
.message-row.mine { align-self: flex-end; justify-content: flex-end; }

/* 아바타 */
.profile-wrapper { margin-right: 12px; flex-shrink: 0; }
.profile-avatar {
  width: 40px; height: 40px; border-radius: 14px; display: flex; align-items: center; justify-content: center;
  background: white; border-color: rgba(0,0,0,0.03);
}

.message-content { display: flex; flex-direction: column; }
.nickname { font-size: 0.75rem; color: #7C4DFF; margin-bottom: 6px; margin-left: 4px; letter-spacing: 0.05em; }

.bubble-wrapper { display: flex; align-items: flex-end; gap: 8px; }
.bubble {
  background: rgba(255, 255, 255, 0.8); padding: 14px 18px; border-radius: 4px 20px 20px 20px;
  font-size: 0.95rem; line-height: 1.5; color: #1a1a1a; font-weight: 600;
  box-shadow: 0 4px 15px rgba(0,0,0,0.03);
}

.mine .bubble {
  background: linear-gradient(135deg, #7C4DFF, #6B3DFF); color: white;
  border-radius: 20px 4px 20px 20px; box-shadow: 0 8px 20px rgba(124, 77, 255, 0.2); border: none;
}

.time { font-size: 0.6rem; opacity: 0.3; margin-bottom: 2px; }

/* 입력창 영역 */
.chat-input-area { padding: 24px; }
.input-wrapper {
  padding: 8px 8px 8px 24px; border-radius: 999px; display: flex; align-items: center; gap: 16px;
  background: rgba(255,255,255,0.6); transition: 0.3s;
}
.input-wrapper:focus-within { background: white; border-color: #7C4DFF; box-shadow: 0 10px 30px rgba(124, 77, 255, 0.1); }
.input-wrapper input {
  flex: 1; background: none; border: none; outline: none; font-weight: 800; font-size: 1rem; color: #1a1a1a;
}
.send-btn {
  width: 48px; height: 48px; border-radius: 50%; border: none; background: #f1f5f9; color: #cbd5e1;
  display: flex; align-items: center; justify-content: center; cursor: pointer; transition: 0.3s;
}
.send-btn.active { background: #1a1a1a; color: white; }
.send-btn.active:hover { background: #7C4DFF; transform: scale(1.1) rotate(-10deg); }

.empty-chat-state { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; }

.page-bottom-hint { text-align: center; font-size: 0.7rem; }

/* Utilities */
.font-black { font-weight: 950; }
.italic { font-style: italic; }
.tracking-tighter { letter-spacing: -0.06em; }
.text-primary { color: #7C4DFF; }
.mt-10 { margin-top: 2.5rem; }

@keyframes slideUp { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }

@media (max-width: 600px) {
  .page-title { font-size: 2.8rem; }
  .chat-card { height: 70vh; }
  .content-wrapper { padding: 0 12px; }
}
</style>
