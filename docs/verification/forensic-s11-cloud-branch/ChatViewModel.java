package com.xiaoduo.deskpet.chat;

/* compiled from: ChatViewModel.kt */
@kotlin.Metadata(d1 = {"\u0000 \u0001\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u000f\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0014\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\"\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0007\n\u0002\u0010\u0012\n\u0002\b\u0003\b\u0007\u0018\u0000 ]2\u00020\u0001:\u0002]^B\r\u0012\u0006\u0010\u0002\u001a\u00020\u0003¢\u0006\u0002\u0010\u0004J\b\u00107\u001a\u000208H\u0002J\u0006\u00109\u001a\u000208J\b\u0010:\u001a\u00020;H\u0002J\u0006\u0010<\u001a\u000208J\u000e\u0010=\u001a\u0002082\u0006\u0010>\u001a\u00020\u000eJ\b\u0010?\u001a\u00020\u000eH\u0002J\u0006\u0010@\u001a\u000208J\u0010\u0010A\u001a\u0002082\u0006\u0010B\u001a\u00020\u0007H\u0002J\b\u0010C\u001a\u000208H\u0002J\u000e\u0010D\u001a\u0002082\u0006\u0010E\u001a\u00020\u000eJ\u0016\u0010F\u001a\u0002082\u0006\u0010>\u001a\u00020\u000e2\u0006\u0010G\u001a\u00020\u000eJ\u0006\u0010H\u001a\u000208J\u000e\u0010I\u001a\u0002082\u0006\u0010J\u001a\u00020\u000eJ\b\u0010K\u001a\u000208H\u0002JT\u0010L\u001a\u0002082\u0006\u0010B\u001a\u00020\u00072\u0006\u0010M\u001a\u00020\u000e2\u0006\u0010J\u001a\u00020\u000e2\u0006\u0010N\u001a\u00020\u000e2\u001a\b\u0002\u0010O\u001a\u0014\u0012\u0010\u0012\u000e\u0012\u0004\u0012\u00020\u000e\u0012\u0004\u0012\u00020\u000e0P0\t2\u000e\b\u0002\u0010Q\u001a\b\u0012\u0004\u0012\u00020\u000e0RH\u0002J\u0014\u0010S\u001a\b\u0012\u0004\u0012\u00020T0\t2\u0006\u0010U\u001a\u00020\u000eJ\u000e\u0010V\u001a\u0002082\u0006\u0010W\u001a\u00020\u000eJ\u000e\u0010X\u001a\u0002082\u0006\u0010>\u001a\u00020\u000eJ\u0016\u0010Y\u001a\u00020\u00132\u0006\u0010Z\u001a\u00020\u000e2\u0006\u0010[\u001a\u00020\\R\u0016\u0010\u0005\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\u00070\u0006X\u0082\u0004¢\u0006\u0002\n\u0000R\u001a\u0010\b\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\n0\t0\u0006X\u0082\u0004¢\u0006\u0002\n\u0000R\u001a\u0010\u000b\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\f0\t0\u0006X\u0082\u0004¢\u0006\u0002\n\u0000R\u0016\u0010\r\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\u000e0\u0006X\u0082\u0004¢\u0006\u0002\n\u0000R\u0014\u0010\u000f\u001a\b\u0012\u0004\u0012\u00020\u00100\u0006X\u0082\u0004¢\u0006\u0002\n\u0000R\u001a\u0010\u0011\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00070\t0\u0006X\u0082\u0004¢\u0006\u0002\n\u0000R\u0014\u0010\u0012\u001a\b\u0012\u0004\u0012\u00020\u00130\u0006X\u0082\u0004¢\u0006\u0002\n\u0000R\u0010\u0010\u0014\u001a\u0004\u0018\u00010\u000eX\u0082\u000e¢\u0006\u0002\n\u0000R\u0014\u0010\u0015\u001a\u00020\u00138BX\u0082\u0004¢\u0006\u0006\u001a\u0004\b\u0016\u0010\u0017R\u0010\u0010\u0018\u001a\u0004\u0018\u00010\u0019X\u0082\u000e¢\u0006\u0002\n\u0000R\u000e\u0010\u001a\u001a\u00020\u001bX\u0082\u0004¢\u0006\u0002\n\u0000R\u0019\u0010\u001c\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\u00070\u001d¢\u0006\b\n\u0000\u001a\u0004\b\u001e\u0010\u001fR\u001d\u0010 \u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\n0\t0\u001d¢\u0006\b\n\u0000\u001a\u0004\b!\u0010\u001fR\u0017\u0010\"\u001a\b\u0012\u0004\u0012\u00020\u00130\u001d¢\u0006\b\n\u0000\u001a\u0004\b#\u0010\u001fR\u001d\u0010$\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\f0\t0\u001d¢\u0006\b\n\u0000\u001a\u0004\b%\u0010\u001fR\u0019\u0010&\u001a\n\u0012\u0006\u0012\u0004\u0018\u00010\u000e0\u001d¢\u0006\b\n\u0000\u001a\u0004\b'\u0010\u001fR\u0017\u0010(\u001a\b\u0012\u0004\u0012\u00020\u00100\u001d¢\u0006\b\n\u0000\u001a\u0004\b)\u0010\u001fR\u001d\u0010*\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00070\t0\u001d¢\u0006\b\n\u0000\u001a\u0004\b+\u0010\u001fR\u000e\u0010,\u001a\u00020-X\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010.\u001a\u00020/X\u0082\u000e¢\u0006\u0002\n\u0000R\u000e\u00100\u001a\u000201X\u0082\u0004¢\u0006\u0002\n\u0000R*\u00102\u001a\u001e\u0012\u0004\u0012\u00020\u000e\u0012\u0004\u0012\u00020\u001903j\u000e\u0012\u0004\u0012\u00020\u000e\u0012\u0004\u0012\u00020\u0019`4X\u0082\u0004¢\u0006\u0002\n\u0000R\u0017\u00105\u001a\b\u0012\u0004\u0012\u00020\u00130\u001d¢\u0006\b\n\u0000\u001a\u0004\b6\u0010\u001f¨\u0006_"}, d2 = {"Lcom/xiaoduo/deskpet/chat/ChatViewModel;", "Landroidx/lifecycle/AndroidViewModel;", "app", "Landroid/app/Application;", "(Landroid/app/Application;)V", "_currentSession", "Lkotlinx/coroutines/flow/MutableStateFlow;", "Lcom/xiaoduo/deskpet/chat/ChatSession;", "_files", "", "Lcom/xiaoduo/deskpet/agui/FileMeta;", "_messages", "Lcom/xiaoduo/deskpet/chat/ChatMessage;", "_pendingScrollMessageId", "", "_phase", "Lcom/xiaoduo/deskpet/chat/ChatViewModel$Phase;", "_sessions", "_voiceActive", "", "activeAssistantId", "busy", "getBusy", "()Z", "chatJob", "Lkotlinx/coroutines/Job;", "client", "Lcom/xiaoduo/deskpet/agui/AguiClient;", "currentSession", "Lkotlinx/coroutines/flow/StateFlow;", "getCurrentSession", "()Lkotlinx/coroutines/flow/StateFlow;", "files", "getFiles", "keyStopActive", "getKeyStopActive", "messages", "getMessages", "pendingScrollMessageId", "getPendingScrollMessageId", "phase", "getPhase", "sessions", "getSessions", "store", "Lcom/xiaoduo/deskpet/chat/ChatStore;", "turnEpoch", "", "turns", "Lcom/xiaoduo/deskpet/chat/ChatTurnLogic;", "uploadJobs", "Ljava/util/HashMap;", "Lkotlin/collections/HashMap;", "voiceActive", "getVoiceActive", "attachVoiceTranscript", "", "cancelCurrentTurn", "cloudClient", "Lcom/xiaoduo/deskpet/cloud/OpenAIChatClient;", "consumePendingScroll", "deleteSession", "sessionId", "newId", "newSession", "persist", "session", "refreshSessions", "removeFile", "localId", "renameSession", "title", "requestScrollToLastRound", "resend", "assistantId", "resetFiles", "runChat", "userText", "wireId", "fileRefs", "Lkotlin/Pair;", "attachedLocalIds", "", "searchSessions", "Lcom/xiaoduo/deskpet/chat/SessionSummary;", "query", "send", com.xiaoduo.deskpet.ui.overlay.DebugBubbleReceiver.EXTRA_TEXT, "switchSession", "uploadFile", "fileName", "bytes", "", "Companion", "Phase", "app_debug"}, k = 1, mv = {1, 9, 0}, xi = 48)
/* loaded from: classes11.dex */
public final class ChatViewModel extends androidx.lifecycle.AndroidViewModel {
    private static final long BUBBLE_HIDE_DELAY_MS = 12000;
    private static final int MAX_UPLOAD_BYTES = 20971520;
    public static final java.lang.String SCROLL_TO_LAST_ROUND = "__last__";
    private static final java.lang.String TAG = "ChatViewModel";
    private final kotlinx.coroutines.flow.MutableStateFlow<com.xiaoduo.deskpet.chat.ChatSession> _currentSession;
    private final kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.agui.FileMeta>> _files;
    private final kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatMessage>> _messages;
    private final kotlinx.coroutines.flow.MutableStateFlow<java.lang.String> _pendingScrollMessageId;
    private final kotlinx.coroutines.flow.MutableStateFlow<com.xiaoduo.deskpet.chat.ChatViewModel.Phase> _phase;
    private final kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatSession>> _sessions;
    private final kotlinx.coroutines.flow.MutableStateFlow<java.lang.Boolean> _voiceActive;
    private volatile java.lang.String activeAssistantId;
    private volatile kotlinx.coroutines.Job chatJob;
    private final com.xiaoduo.deskpet.agui.AguiClient client;
    private final kotlinx.coroutines.flow.StateFlow<com.xiaoduo.deskpet.chat.ChatSession> currentSession;
    private final kotlinx.coroutines.flow.StateFlow<java.util.List<com.xiaoduo.deskpet.agui.FileMeta>> files;
    private final kotlinx.coroutines.flow.StateFlow<java.lang.Boolean> keyStopActive;
    private final kotlinx.coroutines.flow.StateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatMessage>> messages;
    private final kotlinx.coroutines.flow.StateFlow<java.lang.String> pendingScrollMessageId;
    private final kotlinx.coroutines.flow.StateFlow<com.xiaoduo.deskpet.chat.ChatViewModel.Phase> phase;
    private final kotlinx.coroutines.flow.StateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatSession>> sessions;
    private final com.xiaoduo.deskpet.chat.ChatStore store;
    private volatile int turnEpoch;
    private final com.xiaoduo.deskpet.chat.ChatTurnLogic turns;
    private final java.util.HashMap<java.lang.String, kotlinx.coroutines.Job> uploadJobs;
    private final kotlinx.coroutines.flow.StateFlow<java.lang.Boolean> voiceActive;
    public static final int $stable = 8;

    /* compiled from: ChatViewModel.kt */
    @kotlin.Metadata(d1 = {"\u0000\f\n\u0002\u0018\u0002\n\u0002\u0010\u0010\n\u0002\b\u0006\b\u0086\u0081\u0002\u0018\u00002\b\u0012\u0004\u0012\u00020\u00000\u0001B\u0007\b\u0002¢\u0006\u0002\u0010\u0002j\u0002\b\u0003j\u0002\b\u0004j\u0002\b\u0005j\u0002\b\u0006¨\u0006\u0007"}, d2 = {"Lcom/xiaoduo/deskpet/chat/ChatViewModel$Phase;", "", "(Ljava/lang/String;I)V", "IDLE", "SENDING", "STREAMING", "ERROR", "app_debug"}, k = 1, mv = {1, 9, 0}, xi = 48)
    public enum Phase {
        IDLE,
        SENDING,
        STREAMING,
        ERROR;

        private static final /* synthetic */ kotlin.enums.EnumEntries $ENTRIES = kotlin.enums.EnumEntriesKt.enumEntries($VALUES);

        public static kotlin.enums.EnumEntries<com.xiaoduo.deskpet.chat.ChatViewModel.Phase> getEntries() {
            return $ENTRIES;
        }
    }

    /* JADX DEBUG: Don't trust debug lines info. Repeating lines: [101=6, 102=5] */
    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    /* JADX WARN: Code restructure failed: missing block: B:10:0x00ba, code lost:
    
        if (r8 == null) goto L13;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public ChatViewModel(android.app.Application r14) {
        /*
            r13 = this;
            java.lang.String r0 = "app"
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(r14, r0)
            r13.<init>(r14)
            com.xiaoduo.deskpet.chat.ChatStore r0 = new com.xiaoduo.deskpet.chat.ChatStore
            r1 = r14
            android.content.Context r1 = (android.content.Context) r1
            r0.<init>(r1)
            r13.store = r0
            com.xiaoduo.deskpet.agui.AguiClientFactory r0 = com.xiaoduo.deskpet.agui.AguiClientFactory.INSTANCE
            r1 = r14
            android.content.Context r1 = (android.content.Context) r1
            r2 = 2
            r3 = 0
            com.xiaoduo.deskpet.agui.AguiClient r0 = com.xiaoduo.deskpet.agui.AguiClientFactory.create$default(r0, r1, r3, r2, r3)
            r13.client = r0
            com.xiaoduo.deskpet.chat.ChatTurnLogic r0 = new com.xiaoduo.deskpet.chat.ChatTurnLogic
            r1 = 1
            r0.<init>(r3, r1, r3)
            r13.turns = r0
            java.util.List r0 = kotlin.collections.CollectionsKt.emptyList()
            kotlinx.coroutines.flow.MutableStateFlow r0 = kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow(r0)
            r13._messages = r0
            kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatMessage>> r0 = r13._messages
            kotlinx.coroutines.flow.StateFlow r0 = (kotlinx.coroutines.flow.StateFlow) r0
            r13.messages = r0
            com.xiaoduo.deskpet.chat.ChatViewModel$Phase r0 = com.xiaoduo.deskpet.chat.ChatViewModel.Phase.IDLE
            kotlinx.coroutines.flow.MutableStateFlow r0 = kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow(r0)
            r13._phase = r0
            kotlinx.coroutines.flow.MutableStateFlow<com.xiaoduo.deskpet.chat.ChatViewModel$Phase> r0 = r13._phase
            kotlinx.coroutines.flow.StateFlow r0 = (kotlinx.coroutines.flow.StateFlow) r0
            r13.phase = r0
            kotlinx.coroutines.flow.MutableStateFlow r0 = kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow(r3)
            r13._pendingScrollMessageId = r0
            kotlinx.coroutines.flow.MutableStateFlow<java.lang.String> r0 = r13._pendingScrollMessageId
            kotlinx.coroutines.flow.StateFlow r0 = (kotlinx.coroutines.flow.StateFlow) r0
            r13.pendingScrollMessageId = r0
            java.util.List r0 = kotlin.collections.CollectionsKt.emptyList()
            kotlinx.coroutines.flow.MutableStateFlow r0 = kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow(r0)
            r13._sessions = r0
            kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatSession>> r0 = r13._sessions
            kotlinx.coroutines.flow.StateFlow r0 = (kotlinx.coroutines.flow.StateFlow) r0
            r13.sessions = r0
            kotlinx.coroutines.flow.MutableStateFlow r0 = kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow(r3)
            r13._currentSession = r0
            kotlinx.coroutines.flow.MutableStateFlow<com.xiaoduo.deskpet.chat.ChatSession> r0 = r13._currentSession
            kotlinx.coroutines.flow.StateFlow r0 = (kotlinx.coroutines.flow.StateFlow) r0
            r13.currentSession = r0
            java.util.List r0 = kotlin.collections.CollectionsKt.emptyList()
            kotlinx.coroutines.flow.MutableStateFlow r0 = kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow(r0)
            r13._files = r0
            kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.agui.FileMeta>> r0 = r13._files
            kotlinx.coroutines.flow.StateFlow r0 = (kotlinx.coroutines.flow.StateFlow) r0
            r13.files = r0
            java.util.HashMap r0 = new java.util.HashMap
            r0.<init>()
            r13.uploadJobs = r0
            com.xiaoduo.deskpet.chat.ChatStore r0 = r13.store
            com.xiaoduo.deskpet.chat.SessionIndex r0 = r0.loadIndex()
            java.lang.String r2 = r0.getCurrentId()
            if (r2 == 0) goto Lbc
            r4 = 0
            java.util.List r5 = r0.getSessions()
            java.lang.Iterable r5 = (java.lang.Iterable) r5
            r6 = 0
            java.util.Iterator r7 = r5.iterator()
        L9d:
            boolean r8 = r7.hasNext()
            if (r8 == 0) goto Lb6
            java.lang.Object r8 = r7.next()
            r9 = r8
            com.xiaoduo.deskpet.chat.ChatSession r9 = (com.xiaoduo.deskpet.chat.ChatSession) r9
            r10 = 0
            java.lang.String r11 = r9.getId()
            boolean r9 = kotlin.jvm.internal.Intrinsics.areEqual(r11, r2)
            if (r9 == 0) goto L9d
            goto Lb7
        Lb6:
            r8 = r3
        Lb7:
            com.xiaoduo.deskpet.chat.ChatSession r8 = (com.xiaoduo.deskpet.chat.ChatSession) r8
            if (r8 != 0) goto L115
        Lbc:
            java.util.List r2 = r0.getSessions()
            java.lang.Iterable r2 = (java.lang.Iterable) r2
            r4 = 0
            java.util.Iterator r5 = r2.iterator()
            boolean r6 = r5.hasNext()
            if (r6 != 0) goto Lcf
            r6 = r3
            goto Lfd
        Lcf:
            java.lang.Object r6 = r5.next()
            boolean r7 = r5.hasNext()
            if (r7 != 0) goto Lda
            goto Lfd
        Lda:
            r7 = r6
            com.xiaoduo.deskpet.chat.ChatSession r7 = (com.xiaoduo.deskpet.chat.ChatSession) r7
            r8 = 0
            long r7 = r7.getUpdatedAt()
        Le3:
            java.lang.Object r9 = r5.next()
            r10 = r9
            com.xiaoduo.deskpet.chat.ChatSession r10 = (com.xiaoduo.deskpet.chat.ChatSession) r10
            r11 = 0
            long r10 = r10.getUpdatedAt()
            int r12 = (r7 > r10 ? 1 : (r7 == r10 ? 0 : -1))
            if (r12 >= 0) goto Lf6
            r6 = r9
            r7 = r10
        Lf6:
            boolean r9 = r5.hasNext()
            if (r9 != 0) goto Le3
        Lfd:
            r8 = r6
            com.xiaoduo.deskpet.chat.ChatSession r8 = (com.xiaoduo.deskpet.chat.ChatSession) r8
            if (r8 == 0) goto L10e
            r2 = r8
            r4 = 0
            com.xiaoduo.deskpet.chat.ChatStore r5 = r13.store
            java.lang.String r6 = r2.getId()
            r5.setCurrent(r6)
            goto L115
        L10e:
            com.xiaoduo.deskpet.chat.ChatStore r2 = r13.store
            com.xiaoduo.deskpet.chat.ChatSession r8 = r2.createSession()
        L115:
            r2 = r8
            kotlinx.coroutines.flow.MutableStateFlow<com.xiaoduo.deskpet.chat.ChatSession> r4 = r13._currentSession
            r4.setValue(r2)
            kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatSession>> r4 = r13._sessions
            com.xiaoduo.deskpet.chat.ChatStore r5 = r13.store
            com.xiaoduo.deskpet.chat.SessionIndex r5 = r5.loadIndex()
            java.util.List r5 = r5.getSessions()
            java.lang.Iterable r5 = (java.lang.Iterable) r5
            r6 = 0
            com.xiaoduo.deskpet.chat.ChatViewModel$special$$inlined$sortedByDescending$1 r7 = new com.xiaoduo.deskpet.chat.ChatViewModel$special$$inlined$sortedByDescending$1
            r7.<init>()
            java.util.Comparator r7 = (java.util.Comparator) r7
            java.util.List r5 = kotlin.collections.CollectionsKt.sortedWith(r5, r7)
            r4.setValue(r5)
            kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatMessage>> r4 = r13._messages
            com.xiaoduo.deskpet.chat.ChatStore r5 = r13.store
            java.lang.String r6 = r2.getId()
            java.util.List r5 = r5.loadMessages(r6)
            r4.setValue(r5)
            kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatMessage>> r4 = r13._messages
            java.lang.Object r4 = r4.getValue()
            java.util.Collection r4 = (java.util.Collection) r4
            boolean r4 = r4.isEmpty()
            r1 = r1 ^ r4
            if (r1 == 0) goto L198
            com.xiaoduo.deskpet.util.PetLogger r1 = com.xiaoduo.deskpet.util.PetLogger.INSTANCE
            kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatSession>> r4 = r13._sessions
            java.lang.Object r4 = r4.getValue()
            java.util.List r4 = (java.util.List) r4
            int r4 = r4.size()
            kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatMessage>> r5 = r13._messages
            java.lang.Object r5 = r5.getValue()
            java.util.List r5 = (java.util.List) r5
            int r5 = r5.size()
            java.lang.StringBuilder r6 = new java.lang.StringBuilder
            r6.<init>()
            java.lang.String r7 = "session restored: "
            java.lang.StringBuilder r6 = r6.append(r7)
            java.lang.StringBuilder r4 = r6.append(r4)
            java.lang.String r6 = " sessions, "
            java.lang.StringBuilder r4 = r4.append(r6)
            java.lang.StringBuilder r4 = r4.append(r5)
            java.lang.String r5 = " messages"
            java.lang.StringBuilder r4 = r4.append(r5)
            java.lang.String r4 = r4.toString()
            java.lang.String r5 = "ChatViewModel"
            r1.i(r5, r4)
        L198:
            r13.attachVoiceTranscript()
            r0 = 0
            java.lang.Boolean r1 = java.lang.Boolean.valueOf(r0)
            kotlinx.coroutines.flow.MutableStateFlow r1 = kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow(r1)
            r13._voiceActive = r1
            kotlinx.coroutines.flow.MutableStateFlow<java.lang.Boolean> r1 = r13._voiceActive
            kotlinx.coroutines.flow.StateFlow r1 = (kotlinx.coroutines.flow.StateFlow) r1
            r13.voiceActive = r1
            kotlinx.coroutines.flow.MutableStateFlow<java.lang.Boolean> r1 = r13._voiceActive
            kotlinx.coroutines.flow.Flow r1 = (kotlinx.coroutines.flow.Flow) r1
            com.xiaoduo.deskpet.voice.VoiceClientFactory r2 = com.xiaoduo.deskpet.voice.VoiceClientFactory.INSTANCE
            android.app.Application r4 = r13.getApplication()
            android.content.Context r4 = (android.content.Context) r4
            com.xiaoduo.deskpet.voice.VoiceSessionManager r2 = r2.session(r4)
            kotlinx.coroutines.flow.StateFlow r2 = r2.getStateFlow()
            kotlinx.coroutines.flow.Flow r2 = (kotlinx.coroutines.flow.Flow) r2
            com.xiaoduo.deskpet.chat.ChatViewModel$keyStopActive$1 r4 = new com.xiaoduo.deskpet.chat.ChatViewModel$keyStopActive$1
            r4.<init>(r3)
            kotlin.jvm.functions.Function3 r4 = (kotlin.jvm.functions.Function3) r4
            kotlinx.coroutines.flow.Flow r1 = kotlinx.coroutines.flow.FlowKt.combine(r1, r2, r4)
            r2 = r13
            androidx.lifecycle.ViewModel r2 = (androidx.lifecycle.ViewModel) r2
            kotlinx.coroutines.CoroutineScope r2 = androidx.lifecycle.ViewModelKt.getViewModelScope(r2)
            kotlinx.coroutines.flow.SharingStarted$Companion r3 = kotlinx.coroutines.flow.SharingStarted.INSTANCE
            kotlinx.coroutines.flow.SharingStarted r3 = r3.getEagerly()
            java.lang.Boolean r0 = java.lang.Boolean.valueOf(r0)
            kotlinx.coroutines.flow.StateFlow r0 = kotlinx.coroutines.flow.FlowKt.stateIn(r1, r2, r3, r0)
            r13.keyStopActive = r0
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaoduo.deskpet.chat.ChatViewModel.<init>(android.app.Application):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final com.xiaoduo.deskpet.cloud.OpenAIChatClient cloudClient() {
        return com.xiaoduo.deskpet.cloud.CloudClientFactory.INSTANCE.chat(getApplication());
    }

    public final kotlinx.coroutines.flow.StateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatMessage>> getMessages() {
        return this.messages;
    }

    public final kotlinx.coroutines.flow.StateFlow<com.xiaoduo.deskpet.chat.ChatViewModel.Phase> getPhase() {
        return this.phase;
    }

    public final kotlinx.coroutines.flow.StateFlow<java.lang.String> getPendingScrollMessageId() {
        return this.pendingScrollMessageId;
    }

    public final kotlinx.coroutines.flow.StateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatSession>> getSessions() {
        return this.sessions;
    }

    public final kotlinx.coroutines.flow.StateFlow<com.xiaoduo.deskpet.chat.ChatSession> getCurrentSession() {
        return this.currentSession;
    }

    public final kotlinx.coroutines.flow.StateFlow<java.util.List<com.xiaoduo.deskpet.agui.FileMeta>> getFiles() {
        return this.files;
    }

    private final void attachVoiceTranscript() {
        com.xiaoduo.deskpet.voice.VoiceClientFactory.INSTANCE.session(getApplication()).setTranscriptSink(new com.xiaoduo.deskpet.voice.VoiceSessionManager.TranscriptSink() { // from class: com.xiaoduo.deskpet.chat.ChatViewModel$attachVoiceTranscript$1
            private volatile java.lang.String currentAssistantId;
            private final java.lang.StringBuilder rawBuffer = new java.lang.StringBuilder();

            @Override // com.xiaoduo.deskpet.voice.VoiceSessionManager.TranscriptSink
            public void onUserUtterance(java.lang.String text) {
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow;
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow2;
                java.lang.String newId;
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow3;
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow4;
                kotlin.jvm.internal.Intrinsics.checkNotNullParameter(text, "text");
                mutableStateFlow = com.xiaoduo.deskpet.chat.ChatViewModel.this._voiceActive;
                mutableStateFlow.setValue(true);
                mutableStateFlow2 = com.xiaoduo.deskpet.chat.ChatViewModel.this._currentSession;
                com.xiaoduo.deskpet.chat.ChatSession session = (com.xiaoduo.deskpet.chat.ChatSession) mutableStateFlow2.getValue();
                if (session == null) {
                    return;
                }
                newId = com.xiaoduo.deskpet.chat.ChatViewModel.this.newId();
                com.xiaoduo.deskpet.chat.ChatMessage userMsg = new com.xiaoduo.deskpet.chat.ChatMessage(newId, com.xiaoduo.deskpet.chat.ChatMessage.Role.USER, text, com.xiaoduo.deskpet.chat.ChatMessage.Status.DONE, java.lang.System.currentTimeMillis(), null, 32, null);
                mutableStateFlow3 = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                mutableStateFlow4 = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                mutableStateFlow3.setValue(kotlin.collections.CollectionsKt.plus((java.util.Collection<? extends com.xiaoduo.deskpet.chat.ChatMessage>) mutableStateFlow4.getValue(), userMsg));
                com.xiaoduo.deskpet.chat.ChatViewModel.this.persist(session);
            }

            @Override // com.xiaoduo.deskpet.voice.VoiceSessionManager.TranscriptSink
            public void onAssistantDelta(java.lang.String delta) {
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow;
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow2;
                com.xiaoduo.deskpet.chat.ChatTurnLogic chatTurnLogic;
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow3;
                kotlin.jvm.internal.Intrinsics.checkNotNullParameter(delta, "delta");
                java.lang.String id = ensureAssistant();
                this.rawBuffer.append(delta);
                mutableStateFlow = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                java.lang.Iterable $this$none$iv = (java.lang.Iterable) mutableStateFlow.getValue();
                boolean z = true;
                if (!($this$none$iv instanceof java.util.Collection) || !((java.util.Collection) $this$none$iv).isEmpty()) {
                    java.util.Iterator it = $this$none$iv.iterator();
                    while (true) {
                        if (!it.hasNext()) {
                            break;
                        }
                        java.lang.Object element$iv = it.next();
                        com.xiaoduo.deskpet.chat.ChatMessage it2 = (com.xiaoduo.deskpet.chat.ChatMessage) element$iv;
                        if (kotlin.jvm.internal.Intrinsics.areEqual(it2.getId(), id)) {
                            z = false;
                            break;
                        }
                    }
                }
                if (z) {
                    com.xiaoduo.deskpet.util.PetLogger.INSTANCE.w("ChatViewModel", "voice delta dropped: assistant message " + id + " missing from history");
                }
                mutableStateFlow2 = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                chatTurnLogic = com.xiaoduo.deskpet.chat.ChatViewModel.this.turns;
                mutableStateFlow3 = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                mutableStateFlow2.setValue(chatTurnLogic.applyDelta((java.util.List) mutableStateFlow3.getValue(), id, delta));
            }

            @Override // com.xiaoduo.deskpet.voice.VoiceSessionManager.TranscriptSink
            public void onAssistantDone() {
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow;
                com.xiaoduo.deskpet.chat.ChatTurnLogic chatTurnLogic;
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow2;
                java.lang.String id = this.currentAssistantId;
                if (id != null) {
                    mutableStateFlow = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                    chatTurnLogic = com.xiaoduo.deskpet.chat.ChatViewModel.this.turns;
                    mutableStateFlow2 = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                    java.util.List<com.xiaoduo.deskpet.chat.ChatMessage> list = (java.util.List) mutableStateFlow2.getValue();
                    java.lang.String sb = this.rawBuffer.toString();
                    kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(sb, "toString(...)");
                    mutableStateFlow.setValue(chatTurnLogic.markDone(list, id, sb));
                    endTurn();
                    return;
                }
                com.xiaoduo.deskpet.util.PetLogger.INSTANCE.w("ChatViewModel", "voice turn done with no assistant placeholder, nothing to mark");
                endTurn();
            }

            @Override // com.xiaoduo.deskpet.voice.VoiceSessionManager.TranscriptSink
            public void onAssistantError(java.lang.String message) {
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow;
                com.xiaoduo.deskpet.chat.ChatTurnLogic chatTurnLogic;
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow2;
                kotlin.jvm.internal.Intrinsics.checkNotNullParameter(message, "message");
                java.lang.String id = ensureAssistant();
                mutableStateFlow = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                chatTurnLogic = com.xiaoduo.deskpet.chat.ChatViewModel.this.turns;
                mutableStateFlow2 = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                mutableStateFlow.setValue(chatTurnLogic.markError((java.util.List) mutableStateFlow2.getValue(), id));
                endTurn();
                com.xiaoduo.deskpet.util.PetLogger.INSTANCE.w("ChatViewModel", "voice turn failed: " + message);
            }

            private final java.lang.String ensureAssistant() {
                java.lang.String newId;
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow;
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow2;
                java.lang.String it = this.currentAssistantId;
                if (it == null) {
                    newId = com.xiaoduo.deskpet.chat.ChatViewModel.this.newId();
                    com.xiaoduo.deskpet.chat.ChatMessage msg = new com.xiaoduo.deskpet.chat.ChatMessage(newId, com.xiaoduo.deskpet.chat.ChatMessage.Role.ASSISTANT, "", com.xiaoduo.deskpet.chat.ChatMessage.Status.SENDING, java.lang.System.currentTimeMillis(), null, 32, null);
                    mutableStateFlow = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                    mutableStateFlow2 = com.xiaoduo.deskpet.chat.ChatViewModel.this._messages;
                    mutableStateFlow.setValue(kotlin.collections.CollectionsKt.plus((java.util.Collection<? extends com.xiaoduo.deskpet.chat.ChatMessage>) mutableStateFlow2.getValue(), msg));
                    this.currentAssistantId = msg.getId();
                    return msg.getId();
                }
                return it;
            }

            private final void endTurn() {
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow;
                kotlinx.coroutines.flow.MutableStateFlow mutableStateFlow2;
                this.currentAssistantId = null;
                this.rawBuffer.setLength(0);
                mutableStateFlow = com.xiaoduo.deskpet.chat.ChatViewModel.this._voiceActive;
                mutableStateFlow.setValue(false);
                mutableStateFlow2 = com.xiaoduo.deskpet.chat.ChatViewModel.this._currentSession;
                com.xiaoduo.deskpet.chat.ChatSession it = (com.xiaoduo.deskpet.chat.ChatSession) mutableStateFlow2.getValue();
                if (it != null) {
                    com.xiaoduo.deskpet.chat.ChatViewModel.this.persist(it);
                }
            }
        });
    }

    public final kotlinx.coroutines.flow.StateFlow<java.lang.Boolean> getVoiceActive() {
        return this.voiceActive;
    }

    public final kotlinx.coroutines.flow.StateFlow<java.lang.Boolean> getKeyStopActive() {
        return this.keyStopActive;
    }

    public final void cancelCurrentTurn() {
        com.xiaoduo.deskpet.voice.VoiceSessionManager vsm = com.xiaoduo.deskpet.voice.VoiceClientFactory.INSTANCE.session(getApplication());
        if (this._voiceActive.getValue().booleanValue() || vsm.getState() != com.xiaoduo.deskpet.voice.VoiceStateRouter.State.IDLE) {
            com.xiaoduo.deskpet.util.PetLogger.INSTANCE.i(TAG, "barge-in: cancel voice turn (voiceActive=" + this._voiceActive.getValue() + ", vsmState=" + vsm.getState() + ")");
            vsm.cancelCurrentTurn();
            return;
        }
        java.lang.String assistantId = this.activeAssistantId;
        this.turnEpoch++;
        kotlinx.coroutines.Job job = this.chatJob;
        if (job != null) {
            kotlinx.coroutines.Job.DefaultImpls.cancel$default(job, (java.util.concurrent.CancellationException) null, 1, (java.lang.Object) null);
        }
        this.chatJob = null;
        if (assistantId == null) {
            return;
        }
        com.xiaoduo.deskpet.util.PetLogger.INSTANCE.i(TAG, "barge-in: cancel text turn");
        this.activeAssistantId = null;
        this._messages.setValue(this.turns.markCancelledDone(this._messages.getValue(), assistantId));
        com.xiaoduo.deskpet.chat.ChatSession it = this._currentSession.getValue();
        if (it != null) {
            persist(it);
        }
        this._phase.setValue(com.xiaoduo.deskpet.chat.ChatViewModel.Phase.IDLE);
        com.xiaoduo.deskpet.PetOverlayService svc = com.xiaoduo.deskpet.PetOverlayService.INSTANCE.getInstance();
        if (svc == null) {
            return;
        }
        int round = svc.claimBubbleRound(com.xiaoduo.deskpet.ui.overlay.BubbleOwner.CHAT);
        svc.hideBubble(com.xiaoduo.deskpet.ui.overlay.BubbleOwner.CHAT, round);
    }

    public final void requestScrollToLastRound() {
        this._pendingScrollMessageId.setValue(SCROLL_TO_LAST_ROUND);
    }

    public final void consumePendingScroll() {
        this._pendingScrollMessageId.setValue(null);
    }

    private final boolean getBusy() {
        return this._phase.getValue() == com.xiaoduo.deskpet.chat.ChatViewModel.Phase.SENDING || this._phase.getValue() == com.xiaoduo.deskpet.chat.ChatViewModel.Phase.STREAMING || this._voiceActive.getValue().booleanValue();
    }

    public final boolean uploadFile(java.lang.String fileName, byte[] bytes) {
        kotlinx.coroutines.Job launch$default;
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(fileName, "fileName");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(bytes, "bytes");
        if (bytes.length > MAX_UPLOAD_BYTES) {
            com.xiaoduo.deskpet.util.PetLogger.INSTANCE.w(TAG, "upload rejected: " + fileName + " " + bytes.length + " bytes > 20MB");
            return false;
        }
        com.xiaoduo.deskpet.chat.ChatSession it = this._currentSession.getValue();
        if (it == null) {
            it = this.store.createSession();
            this._currentSession.setValue(it);
            refreshSessions();
        }
        com.xiaoduo.deskpet.chat.ChatSession session = it;
        com.xiaoduo.deskpet.agui.FileMeta placeholder = new com.xiaoduo.deskpet.agui.FileMeta("", fileName, "", bytes.length, kotlin.text.StringsKt.substringAfterLast(fileName, '.', ""), com.xiaoduo.deskpet.agui.UploadStatus.UPLOADING, null, 64, null);
        this._files.setValue(kotlin.collections.CollectionsKt.plus((java.util.Collection<? extends com.xiaoduo.deskpet.agui.FileMeta>) this._files.getValue(), placeholder));
        com.xiaoduo.deskpet.util.PetLogger.INSTANCE.i(TAG, "upload start: " + fileName + " " + bytes.length + " bytes");
        java.util.HashMap<java.lang.String, kotlinx.coroutines.Job> hashMap = this.uploadJobs;
        java.lang.String localId = placeholder.getLocalId();
        launch$default = kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(androidx.lifecycle.ViewModelKt.getViewModelScope(this), kotlinx.coroutines.Dispatchers.getIO(), null, new com.xiaoduo.deskpet.chat.ChatViewModel$uploadFile$1(this, fileName, bytes, session, placeholder, null), 2, null);
        hashMap.put(localId, launch$default);
        return true;
    }

    public final void removeFile(java.lang.String localId) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(localId, "localId");
        kotlinx.coroutines.Job remove = this.uploadJobs.remove(localId);
        if (remove != null) {
            kotlinx.coroutines.Job.DefaultImpls.cancel$default(remove, (java.util.concurrent.CancellationException) null, 1, (java.lang.Object) null);
        }
        kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.agui.FileMeta>> mutableStateFlow = this._files;
        java.lang.Iterable $this$filterNot$iv = this._files.getValue();
        java.util.Collection destination$iv$iv = new java.util.ArrayList();
        for (java.lang.Object element$iv$iv : $this$filterNot$iv) {
            com.xiaoduo.deskpet.agui.FileMeta it = (com.xiaoduo.deskpet.agui.FileMeta) element$iv$iv;
            if (!kotlin.jvm.internal.Intrinsics.areEqual(it.getLocalId(), localId)) {
                destination$iv$iv.add(element$iv$iv);
            }
        }
        mutableStateFlow.setValue((java.util.List) destination$iv$iv);
    }

    private final void resetFiles() {
        java.lang.Iterable values = this.uploadJobs.values();
        kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(values, "<get-values>(...)");
        java.lang.Iterable $this$forEach$iv = values;
        for (java.lang.Object element$iv : $this$forEach$iv) {
            kotlinx.coroutines.Job it = (kotlinx.coroutines.Job) element$iv;
            kotlin.jvm.internal.Intrinsics.checkNotNull(it);
            kotlinx.coroutines.Job.DefaultImpls.cancel$default(it, (java.util.concurrent.CancellationException) null, 1, (java.lang.Object) null);
        }
        this.uploadJobs.clear();
        if (!this._files.getValue().isEmpty()) {
            this._files.setValue(kotlin.collections.CollectionsKt.emptyList());
        }
    }

    public final void send(java.lang.String text) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(text, "text");
        java.lang.String trimmed = kotlin.text.StringsKt.trim((java.lang.CharSequence) text).toString();
        if ((trimmed.length() == 0) || getBusy()) {
            if (trimmed.length() > 0) {
                com.xiaoduo.deskpet.util.PetLogger.INSTANCE.d(TAG, "send ignored: busy");
                return;
            }
            return;
        }
        if (com.xiaoduo.deskpet.chat.FileListRules.INSTANCE.uploadInFlight(this._files.getValue())) {
            com.xiaoduo.deskpet.util.PetLogger.INSTANCE.d(TAG, "send ignored: upload in flight");
            return;
        }
        com.xiaoduo.deskpet.chat.ChatSession it = this._currentSession.getValue();
        if (it == null) {
            it = this.store.createSession();
            this._currentSession.setValue(it);
            refreshSessions();
        }
        com.xiaoduo.deskpet.chat.ChatSession session = it;
        long now = java.lang.System.currentTimeMillis();
        java.lang.String wireId = this.turns.nextWireId();
        com.xiaoduo.deskpet.chat.ChatMessage userMsg = new com.xiaoduo.deskpet.chat.ChatMessage(newId(), com.xiaoduo.deskpet.chat.ChatMessage.Role.USER, trimmed, com.xiaoduo.deskpet.chat.ChatMessage.Status.DONE, now, wireId);
        com.xiaoduo.deskpet.chat.ChatMessage assistant = new com.xiaoduo.deskpet.chat.ChatMessage(newId(), com.xiaoduo.deskpet.chat.ChatMessage.Role.ASSISTANT, "", com.xiaoduo.deskpet.chat.ChatMessage.Status.SENDING, now, null, 32, null);
        this._messages.setValue(this.turns.appendTurn(this._messages.getValue(), userMsg, assistant));
        persist(session);
        java.lang.Iterable attached = com.xiaoduo.deskpet.chat.FileListRules.INSTANCE.attachedDone(this._files.getValue());
        java.lang.String id = assistant.getId();
        java.lang.Iterable $this$map$iv = attached;
        java.util.Collection destination$iv$iv = new java.util.ArrayList(kotlin.collections.CollectionsKt.collectionSizeOrDefault($this$map$iv, 10));
        for (java.lang.Object item$iv$iv : $this$map$iv) {
            com.xiaoduo.deskpet.chat.FileListRules.Attached it2 = (com.xiaoduo.deskpet.chat.FileListRules.Attached) item$iv$iv;
            destination$iv$iv.add(kotlin.TuplesKt.to(it2.getFileId(), it2.getFileName()));
            $this$map$iv = $this$map$iv;
        }
        java.util.ArrayList arrayList = (java.util.List) destination$iv$iv;
        java.lang.Iterable $this$map$iv2 = attached;
        java.util.Collection destination$iv$iv2 = new java.util.ArrayList(kotlin.collections.CollectionsKt.collectionSizeOrDefault($this$map$iv2, 10));
        for (java.lang.Object item$iv$iv2 : $this$map$iv2) {
            destination$iv$iv2.add(((com.xiaoduo.deskpet.chat.FileListRules.Attached) item$iv$iv2).getLocalId());
        }
        runChat(session, trimmed, id, wireId, arrayList, kotlin.collections.CollectionsKt.toSet((java.util.List) destination$iv$iv2));
    }

    public final void resend(java.lang.String assistantId) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(assistantId, "assistantId");
        if (getBusy()) {
            return;
        }
        if (com.xiaoduo.deskpet.chat.FileListRules.INSTANCE.uploadInFlight(this._files.getValue())) {
            com.xiaoduo.deskpet.util.PetLogger.INSTANCE.d(TAG, "resend ignored: upload in flight");
            return;
        }
        com.xiaoduo.deskpet.chat.ChatSession session = this._currentSession.getValue();
        if (session == null) {
            return;
        }
        com.xiaoduo.deskpet.chat.ChatTurnLogic.ResendPlan plan = com.xiaoduo.deskpet.chat.ChatTurnLogic.prepareResend$default(this.turns, this._messages.getValue(), assistantId, null, 4, null);
        if (plan == null) {
            com.xiaoduo.deskpet.util.PetLogger.INSTANCE.w(TAG, "resend aborted: no resendable error turn for " + assistantId);
            return;
        }
        this._messages.setValue(plan.getMessages());
        com.xiaoduo.deskpet.util.PetLogger.INSTANCE.i(TAG, "resend assistantMsg=" + assistantId);
        java.lang.Iterable attached = com.xiaoduo.deskpet.chat.FileListRules.INSTANCE.attachedDone(this._files.getValue());
        java.lang.String userText = plan.getUserText();
        java.lang.String assistantId2 = plan.getAssistantId();
        java.lang.String wireId = plan.getWireId();
        java.lang.Iterable $this$map$iv = attached;
        java.util.Collection destination$iv$iv = new java.util.ArrayList(kotlin.collections.CollectionsKt.collectionSizeOrDefault($this$map$iv, 10));
        for (java.lang.Object item$iv$iv : $this$map$iv) {
            com.xiaoduo.deskpet.chat.FileListRules.Attached it = (com.xiaoduo.deskpet.chat.FileListRules.Attached) item$iv$iv;
            destination$iv$iv.add(kotlin.TuplesKt.to(it.getFileId(), it.getFileName()));
            $this$map$iv = $this$map$iv;
        }
        java.util.ArrayList arrayList = (java.util.List) destination$iv$iv;
        java.lang.Iterable $this$map$iv2 = attached;
        java.util.Collection destination$iv$iv2 = new java.util.ArrayList(kotlin.collections.CollectionsKt.collectionSizeOrDefault($this$map$iv2, 10));
        for (java.lang.Object item$iv$iv2 : $this$map$iv2) {
            destination$iv$iv2.add(((com.xiaoduo.deskpet.chat.FileListRules.Attached) item$iv$iv2).getLocalId());
            $this$map$iv2 = $this$map$iv2;
        }
        runChat(session, userText, assistantId2, wireId, arrayList, kotlin.collections.CollectionsKt.toSet((java.util.List) destination$iv$iv2));
    }

    public final void newSession() {
        if (getBusy()) {
            com.xiaoduo.deskpet.util.PetLogger.INSTANCE.d(TAG, "newSession ignored: busy");
            return;
        }
        com.xiaoduo.deskpet.chat.ChatSession session = this.store.createSession();
        this.turns.reset();
        resetFiles();
        this._phase.setValue(com.xiaoduo.deskpet.chat.ChatViewModel.Phase.IDLE);
        this._messages.setValue(kotlin.collections.CollectionsKt.emptyList());
        this._currentSession.setValue(session);
        refreshSessions();
        com.xiaoduo.deskpet.util.PetLogger.INSTANCE.i(TAG, "new session, " + this._sessions.getValue().size() + " total");
    }

    public final void switchSession(java.lang.String sessionId) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(sessionId, "sessionId");
        if (getBusy()) {
            com.xiaoduo.deskpet.util.PetLogger.INSTANCE.d(TAG, "switchSession ignored: busy");
            return;
        }
        com.xiaoduo.deskpet.chat.ChatSession value = this._currentSession.getValue();
        java.lang.Object obj = null;
        if (kotlin.jvm.internal.Intrinsics.areEqual(sessionId, value != null ? value.getId() : null)) {
            return;
        }
        com.xiaoduo.deskpet.chat.SessionIndex index = this.store.loadIndex();
        java.lang.Iterable $this$firstOrNull$iv = index.getSessions();
        java.util.Iterator it = $this$firstOrNull$iv.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            java.lang.Object element$iv = it.next();
            com.xiaoduo.deskpet.chat.ChatSession it2 = (com.xiaoduo.deskpet.chat.ChatSession) element$iv;
            if (kotlin.jvm.internal.Intrinsics.areEqual(it2.getId(), sessionId)) {
                obj = element$iv;
                break;
            }
        }
        com.xiaoduo.deskpet.chat.ChatSession target = (com.xiaoduo.deskpet.chat.ChatSession) obj;
        if (target == null) {
            return;
        }
        this.store.setCurrent(sessionId);
        this.turns.reset();
        resetFiles();
        this._phase.setValue(com.xiaoduo.deskpet.chat.ChatViewModel.Phase.IDLE);
        this._messages.setValue(this.store.loadMessages(sessionId));
        this._currentSession.setValue(target);
        refreshSessions();
        com.xiaoduo.deskpet.util.PetLogger.INSTANCE.i(TAG, "switched session, " + this._messages.getValue().size() + " messages");
    }

    /* JADX DEBUG: Don't trust debug lines info. Repeating lines: [490=5] */
    /* JADX WARN: Code restructure failed: missing block: B:20:0x0061, code lost:
    
        if (r8 == null) goto L23;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void deleteSession(java.lang.String r13) {
        /*
            r12 = this;
            java.lang.String r0 = "sessionId"
            kotlin.jvm.internal.Intrinsics.checkNotNullParameter(r13, r0)
            boolean r0 = r12.getBusy()
            java.lang.String r1 = "ChatViewModel"
            if (r0 == 0) goto L15
            com.xiaoduo.deskpet.util.PetLogger r0 = com.xiaoduo.deskpet.util.PetLogger.INSTANCE
            java.lang.String r2 = "deleteSession ignored: busy"
            r0.d(r1, r2)
            return
        L15:
            com.xiaoduo.deskpet.chat.ChatStore r0 = r12.store
            com.xiaoduo.deskpet.chat.SessionIndex r0 = r0.deleteSession(r13)
            kotlinx.coroutines.flow.MutableStateFlow<com.xiaoduo.deskpet.chat.ChatSession> r2 = r12._currentSession
            java.lang.Object r2 = r2.getValue()
            com.xiaoduo.deskpet.chat.ChatSession r2 = (com.xiaoduo.deskpet.chat.ChatSession) r2
            r3 = 0
            if (r2 == 0) goto L2b
            java.lang.String r2 = r2.getId()
            goto L2c
        L2b:
            r2 = r3
        L2c:
            boolean r2 = kotlin.jvm.internal.Intrinsics.areEqual(r13, r2)
            if (r2 == 0) goto L8d
            java.lang.String r2 = r0.getCurrentId()
            if (r2 == 0) goto L63
            r4 = 0
            java.util.List r5 = r0.getSessions()
            java.lang.Iterable r5 = (java.lang.Iterable) r5
            r6 = 0
            java.util.Iterator r7 = r5.iterator()
        L44:
            boolean r8 = r7.hasNext()
            if (r8 == 0) goto L5d
            java.lang.Object r8 = r7.next()
            r9 = r8
            com.xiaoduo.deskpet.chat.ChatSession r9 = (com.xiaoduo.deskpet.chat.ChatSession) r9
            r10 = 0
            java.lang.String r11 = r9.getId()
            boolean r9 = kotlin.jvm.internal.Intrinsics.areEqual(r11, r2)
            if (r9 == 0) goto L44
            goto L5e
        L5d:
            r8 = r3
        L5e:
            com.xiaoduo.deskpet.chat.ChatSession r8 = (com.xiaoduo.deskpet.chat.ChatSession) r8
            if (r8 != 0) goto L69
        L63:
            com.xiaoduo.deskpet.chat.ChatStore r2 = r12.store
            com.xiaoduo.deskpet.chat.ChatSession r8 = r2.createSession()
        L69:
            r2 = r8
            com.xiaoduo.deskpet.chat.ChatTurnLogic r4 = r12.turns
            r4.reset()
            r12.resetFiles()
            kotlinx.coroutines.flow.MutableStateFlow<com.xiaoduo.deskpet.chat.ChatViewModel$Phase> r4 = r12._phase
            com.xiaoduo.deskpet.chat.ChatViewModel$Phase r5 = com.xiaoduo.deskpet.chat.ChatViewModel.Phase.IDLE
            r4.setValue(r5)
            kotlinx.coroutines.flow.MutableStateFlow<com.xiaoduo.deskpet.chat.ChatSession> r4 = r12._currentSession
            r4.setValue(r2)
            kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatMessage>> r4 = r12._messages
            com.xiaoduo.deskpet.chat.ChatStore r5 = r12.store
            java.lang.String r6 = r2.getId()
            java.util.List r5 = r5.loadMessages(r6)
            r4.setValue(r5)
        L8d:
            r12.refreshSessions()
            com.xiaoduo.deskpet.util.PetLogger r2 = com.xiaoduo.deskpet.util.PetLogger.INSTANCE
            java.util.List r4 = r0.getSessions()
            int r4 = r4.size()
            java.lang.StringBuilder r5 = new java.lang.StringBuilder
            r5.<init>()
            java.lang.String r6 = "session deleted, "
            java.lang.StringBuilder r5 = r5.append(r6)
            java.lang.StringBuilder r4 = r5.append(r4)
            java.lang.String r5 = " remaining"
            java.lang.StringBuilder r4 = r4.append(r5)
            java.lang.String r4 = r4.toString()
            r2.i(r1, r4)
            r1 = r12
            androidx.lifecycle.ViewModel r1 = (androidx.lifecycle.ViewModel) r1
            kotlinx.coroutines.CoroutineScope r4 = androidx.lifecycle.ViewModelKt.getViewModelScope(r1)
            kotlinx.coroutines.CoroutineDispatcher r1 = kotlinx.coroutines.Dispatchers.getIO()
            r5 = r1
            kotlin.coroutines.CoroutineContext r5 = (kotlin.coroutines.CoroutineContext) r5
            com.xiaoduo.deskpet.chat.ChatViewModel$deleteSession$1 r1 = new com.xiaoduo.deskpet.chat.ChatViewModel$deleteSession$1
            r1.<init>(r12, r13, r3)
            r7 = r1
            kotlin.jvm.functions.Function2 r7 = (kotlin.jvm.functions.Function2) r7
            r8 = 2
            r9 = 0
            r6 = 0
            kotlinx.coroutines.BuildersKt.launch$default(r4, r5, r6, r7, r8, r9)
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaoduo.deskpet.chat.ChatViewModel.deleteSession(java.lang.String):void");
    }

    public final java.util.List<com.xiaoduo.deskpet.chat.SessionSummary> searchSessions(java.lang.String query) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(query, "query");
        return this.store.searchSessions(query);
    }

    public final void renameSession(java.lang.String sessionId, java.lang.String title) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(sessionId, "sessionId");
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(title, "title");
        if (getBusy()) {
            com.xiaoduo.deskpet.util.PetLogger.INSTANCE.d(TAG, "renameSession ignored: busy");
            return;
        }
        com.xiaoduo.deskpet.chat.ChatSession updated = this.store.renameSession(sessionId, title);
        if (updated == null) {
            return;
        }
        refreshSessions();
        com.xiaoduo.deskpet.util.PetLogger.INSTANCE.i(TAG, "session renamed: " + updated.getTitle().length() + " chars");
        kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(androidx.lifecycle.ViewModelKt.getViewModelScope(this), kotlinx.coroutines.Dispatchers.getIO(), null, new com.xiaoduo.deskpet.chat.ChatViewModel$renameSession$1(this, sessionId, updated, null), 2, null);
    }

    static /* synthetic */ void runChat$default(com.xiaoduo.deskpet.chat.ChatViewModel chatViewModel, com.xiaoduo.deskpet.chat.ChatSession chatSession, java.lang.String str, java.lang.String str2, java.lang.String str3, java.util.List list, java.util.Set set, int i, java.lang.Object obj) {
        java.util.List list2;
        java.util.Set set2;
        if ((i & 16) == 0) {
            list2 = list;
        } else {
            list2 = kotlin.collections.CollectionsKt.emptyList();
        }
        if ((i & 32) == 0) {
            set2 = set;
        } else {
            set2 = kotlin.collections.SetsKt.emptySet();
        }
        chatViewModel.runChat(chatSession, str, str2, str3, list2, set2);
    }

    private final void runChat(com.xiaoduo.deskpet.chat.ChatSession session, java.lang.String userText, java.lang.String assistantId, java.lang.String wireId, java.util.List<kotlin.Pair<java.lang.String, java.lang.String>> fileRefs, java.util.Set<java.lang.String> attachedLocalIds) {
        kotlinx.coroutines.Job launch$default;
        this._phase.setValue(com.xiaoduo.deskpet.chat.ChatViewModel.Phase.SENDING);
        this.activeAssistantId = assistantId;
        int myEpoch = this.turnEpoch;
        com.xiaoduo.deskpet.PetOverlayService svc = com.xiaoduo.deskpet.PetOverlayService.INSTANCE.getInstance();
        int myRound = svc != null ? svc.claimBubbleRound(com.xiaoduo.deskpet.ui.overlay.BubbleOwner.CHAT) : 0;
        java.lang.String threadName = com.xiaoduo.deskpet.chat.ChatSession.INSTANCE.deriveTitle(this._messages.getValue());
        com.xiaoduo.deskpet.util.PetLogger.INSTANCE.i(TAG, "chat send: " + userText.length() + " chars");
        launch$default = kotlinx.coroutines.BuildersKt__Builders_commonKt.launch$default(androidx.lifecycle.ViewModelKt.getViewModelScope(this), kotlinx.coroutines.Dispatchers.getIO(), null, new com.xiaoduo.deskpet.chat.ChatViewModel$runChat$1(this, userText, wireId, session, threadName, fileRefs, myEpoch, assistantId, attachedLocalIds, svc, myRound, null), 2, null);
        this.chatJob = launch$default;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void persist(com.xiaoduo.deskpet.chat.ChatSession session) {
        this.store.saveMessages(session.getId(), this._messages.getValue());
        refreshSessions();
    }

    private final void refreshSessions() {
        java.lang.Object element$iv;
        com.xiaoduo.deskpet.chat.SessionIndex index = this.store.loadIndex();
        kotlinx.coroutines.flow.MutableStateFlow<java.util.List<com.xiaoduo.deskpet.chat.ChatSession>> mutableStateFlow = this._sessions;
        java.lang.Iterable $this$sortedByDescending$iv = index.getSessions();
        mutableStateFlow.setValue(kotlin.collections.CollectionsKt.sortedWith($this$sortedByDescending$iv, new java.util.Comparator() { // from class: com.xiaoduo.deskpet.chat.ChatViewModel$refreshSessions$$inlined$sortedByDescending$1
            /* JADX DEBUG: Multi-variable search result rejected for r6v0, resolved type: T */
            /* JADX DEBUG: Multi-variable search result rejected for r7v0, resolved type: T */
            /* JADX WARN: Multi-variable type inference failed */
            @Override // java.util.Comparator
            public final int compare(T t, T t2) {
                com.xiaoduo.deskpet.chat.ChatSession it = (com.xiaoduo.deskpet.chat.ChatSession) t2;
                com.xiaoduo.deskpet.chat.ChatSession it2 = (com.xiaoduo.deskpet.chat.ChatSession) t;
                return kotlin.comparisons.ComparisonsKt.compareValues(java.lang.Long.valueOf(it.getUpdatedAt()), java.lang.Long.valueOf(it2.getUpdatedAt()));
            }
        }));
        com.xiaoduo.deskpet.chat.ChatSession cur = this._currentSession.getValue();
        if (cur == null) {
            return;
        }
        kotlinx.coroutines.flow.MutableStateFlow<com.xiaoduo.deskpet.chat.ChatSession> mutableStateFlow2 = this._currentSession;
        java.lang.Iterable $this$firstOrNull$iv = index.getSessions();
        java.util.Iterator it = $this$firstOrNull$iv.iterator();
        while (true) {
            if (it.hasNext()) {
                element$iv = it.next();
                com.xiaoduo.deskpet.chat.ChatSession it2 = (com.xiaoduo.deskpet.chat.ChatSession) element$iv;
                if (kotlin.jvm.internal.Intrinsics.areEqual(it2.getId(), cur.getId())) {
                    break;
                }
            } else {
                element$iv = null;
                break;
            }
        }
        com.xiaoduo.deskpet.chat.ChatSession chatSession = (com.xiaoduo.deskpet.chat.ChatSession) element$iv;
        if (chatSession == null) {
            chatSession = cur;
        }
        mutableStateFlow2.setValue(chatSession);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final java.lang.String newId() {
        java.lang.String uuid = java.util.UUID.randomUUID().toString();
        kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(uuid, "toString(...)");
        return uuid;
    }
}
