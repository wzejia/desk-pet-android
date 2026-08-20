package com.xiaoduo.deskpet.chat;

/* compiled from: ChatViewModel.kt */
@kotlin.Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\u008a@"}, d2 = {"<anonymous>", "", "Lkotlinx/coroutines/CoroutineScope;"}, k = 3, mv = {1, 9, 0}, xi = 48)
@kotlin.coroutines.jvm.internal.DebugMetadata(c = "com.xiaoduo.deskpet.chat.ChatViewModel$runChat$1", f = "ChatViewModel.kt", i = {}, l = {}, m = "invokeSuspend", n = {}, s = {})
/* loaded from: classes11.dex */
final class ChatViewModel$runChat$1 extends kotlin.coroutines.jvm.internal.SuspendLambda implements kotlin.jvm.functions.Function2<kotlinx.coroutines.CoroutineScope, kotlin.coroutines.Continuation<? super kotlin.Unit>, java.lang.Object> {
    final /* synthetic */ java.lang.String $assistantId;
    final /* synthetic */ java.util.Set<java.lang.String> $attachedLocalIds;
    final /* synthetic */ java.util.List<kotlin.Pair<java.lang.String, java.lang.String>> $fileRefs;
    final /* synthetic */ int $myEpoch;
    final /* synthetic */ int $myRound;
    final /* synthetic */ com.xiaoduo.deskpet.chat.ChatSession $session;
    final /* synthetic */ com.xiaoduo.deskpet.PetOverlayService $svc;
    final /* synthetic */ java.lang.String $threadName;
    final /* synthetic */ java.lang.String $userText;
    final /* synthetic */ java.lang.String $wireId;
    int label;
    final /* synthetic */ com.xiaoduo.deskpet.chat.ChatViewModel this$0;

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    ChatViewModel$runChat$1(com.xiaoduo.deskpet.chat.ChatViewModel chatViewModel, java.lang.String str, java.lang.String str2, com.xiaoduo.deskpet.chat.ChatSession chatSession, java.lang.String str3, java.util.List<kotlin.Pair<java.lang.String, java.lang.String>> list, int i, java.lang.String str4, java.util.Set<java.lang.String> set, com.xiaoduo.deskpet.PetOverlayService petOverlayService, int i2, kotlin.coroutines.Continuation<? super com.xiaoduo.deskpet.chat.ChatViewModel$runChat$1> continuation) {
        super(2, continuation);
        this.this$0 = chatViewModel;
        this.$userText = str;
        this.$wireId = str2;
        this.$session = chatSession;
        this.$threadName = str3;
        this.$fileRefs = list;
        this.$myEpoch = i;
        this.$assistantId = str4;
        this.$attachedLocalIds = set;
        this.$svc = petOverlayService;
        this.$myRound = i2;
    }

    @Override // kotlin.coroutines.jvm.internal.BaseContinuationImpl
    public final kotlin.coroutines.Continuation<kotlin.Unit> create(java.lang.Object obj, kotlin.coroutines.Continuation<?> continuation) {
        return new com.xiaoduo.deskpet.chat.ChatViewModel$runChat$1(this.this$0, this.$userText, this.$wireId, this.$session, this.$threadName, this.$fileRefs, this.$myEpoch, this.$assistantId, this.$attachedLocalIds, this.$svc, this.$myRound, continuation);
    }

    /* JADX DEBUG: Method merged with bridge method: invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object; */
    @Override // kotlin.jvm.functions.Function2
    public final java.lang.Object invoke(kotlinx.coroutines.CoroutineScope coroutineScope, kotlin.coroutines.Continuation<? super kotlin.Unit> continuation) {
        return ((com.xiaoduo.deskpet.chat.ChatViewModel$runChat$1) create(coroutineScope, continuation)).invokeSuspend(kotlin.Unit.INSTANCE);
    }

    /* JADX DEBUG: Another duplicated slice has different insns count: {[IGET, IGET, INVOKE]}, finally: {[IGET, IGET, INVOKE, IGET, CONST, INVOKE, IGET, INVOKE, IF] complete} */
    /* JADX DEBUG: Don't trust debug lines info. Repeating lines: [662=5, 671=5, 672=5, 673=4] */
    /* JADX DEBUG: Failed to insert an additional move for type inference into block B:92:0x02f5 */
    /* JADX DEBUG: Multi-variable search result rejected for r12v15, resolved type: com.xiaoduo.deskpet.chat.ChatMessage */
    /* JADX DEBUG: Multi-variable search result rejected for r12v2, resolved type: com.xiaoduo.deskpet.PetOverlayService */
    /* JADX WARN: Code restructure failed: missing block: B:120:0x03bc, code lost:
    
        if (r0 == r1) goto L119;
     */
    /* JADX WARN: Code restructure failed: missing block: B:79:0x02f1, code lost:
    
        if (r0 == r1) goto L119;
     */
    /* JADX WARN: Code restructure failed: missing block: B:81:0x03cc, code lost:
    
        return kotlin.Unit.INSTANCE;
     */
    /* JADX WARN: Code restructure failed: missing block: B:82:0x03be, code lost:
    
        r25.this$0.activeAssistantId = null;
        r25.this$0.chatJob = null;
     */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:22:0x009e A[Catch: all -> 0x02f7, TryCatch #1 {all -> 0x02f7, blocks: (B:11:0x0053, B:12:0x0072, B:14:0x0078, B:16:0x0088, B:22:0x009e, B:29:0x00a3, B:30:0x00bd, B:32:0x00c3, B:34:0x00e0, B:36:0x00f8, B:37:0x014b, B:39:0x0156, B:44:0x016f, B:47:0x0173, B:48:0x01ae, B:50:0x01b4, B:54:0x01c8, B:56:0x01cc, B:59:0x01d6, B:61:0x01f4, B:62:0x021c, B:64:0x0222, B:66:0x0237, B:132:0x010f, B:134:0x0113, B:135:0x0122, B:136:0x0127, B:137:0x0128), top: B:9:0x0051 }] */
    /* JADX WARN: Removed duplicated region for block: B:25:0x00a1 A[SYNTHETIC] */
    @Override // kotlin.coroutines.jvm.internal.BaseContinuationImpl
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object invokeSuspend(java.lang.Object r26) {
        /*
            r25 = this;
            java.lang.String r0 = ""
            java.lang.String r1 = "ChatViewModel"
            kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()
            r2 = r25
            int r3 = r2.label
            switch(r3) {
                case 0: goto L16;
                default: goto Le;
            }
        Le:
            java.lang.IllegalStateException r0 = new java.lang.IllegalStateException
            java.lang.String r1 = "call to 'resume' before 'invoke' with coroutine"
            r0.<init>(r1)
            throw r0
        L16:
            kotlin.ResultKt.throwOnFailure(r26)
            r3 = r25
            r4 = r26
            r6 = 1
            kotlin.jvm.internal.Ref$BooleanRef r13 = new kotlin.jvm.internal.Ref$BooleanRef     // Catch: java.lang.Throwable -> L2fa
            r13.<init>()     // Catch: java.lang.Throwable -> L2fa
            kotlin.jvm.internal.Ref$ObjectRef r8 = new kotlin.jvm.internal.Ref$ObjectRef     // Catch: java.lang.Throwable -> L2fa
            r8.<init>()     // Catch: java.lang.Throwable -> L2fa
            r15 = r8
            r15.element = r0     // Catch: java.lang.Throwable -> L2fa
            com.xiaoduo.deskpet.chat.ChatViewModel$runChat$1$onDelta$1 r16 = new com.xiaoduo.deskpet.chat.ChatViewModel$runChat$1$onDelta$1     // Catch: java.lang.Throwable -> L2fa
            int r9 = r3.$myEpoch     // Catch: java.lang.Throwable -> L2fa
            com.xiaoduo.deskpet.chat.ChatViewModel r10 = r3.this$0     // Catch: java.lang.Throwable -> L2fa
            java.lang.String r11 = r3.$assistantId     // Catch: java.lang.Throwable -> L2fa
            com.xiaoduo.deskpet.PetOverlayService r12 = r3.$svc     // Catch: java.lang.Throwable -> L2fa
            int r14 = r3.$myRound     // Catch: java.lang.Throwable -> L2fa
            r8 = r16
            r17 = r15
            r8.<init>()     // Catch: java.lang.Throwable -> L2fa
            kotlin.jvm.functions.Function1 r16 = (kotlin.jvm.functions.Function1) r16     // Catch: java.lang.Throwable -> L2fa
            r8 = r16
            com.xiaoduo.deskpet.net.NetConfig r9 = com.xiaoduo.deskpet.net.NetConfig.INSTANCE     // Catch: java.lang.Throwable -> L2fa
            com.xiaoduo.deskpet.chat.ChatViewModel r10 = r3.this$0     // Catch: java.lang.Throwable -> L2fa
            android.app.Application r10 = r10.getApplication()     // Catch: java.lang.Throwable -> L2fa
            android.content.Context r10 = (android.content.Context) r10     // Catch: java.lang.Throwable -> L2fa
            boolean r9 = r9.llmIsCloud(r10)     // Catch: java.lang.Throwable -> L2fa
            if (r9 == 0) goto L128
            com.xiaoduo.deskpet.chat.ChatViewModel r9 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            kotlinx.coroutines.flow.MutableStateFlow r9 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_messages$p(r9)     // Catch: java.lang.Throwable -> L2f7
            java.lang.Object r9 = r9.getValue()     // Catch: java.lang.Throwable -> L2f7
            java.util.List r9 = (java.util.List) r9     // Catch: java.lang.Throwable -> L2f7
            java.util.List r9 = kotlin.collections.CollectionsKt.dropLast(r9, r6)     // Catch: java.lang.Throwable -> L2f7
            java.lang.Iterable r9 = (java.lang.Iterable) r9     // Catch: java.lang.Throwable -> L2f7
            r10 = 0
            java.util.ArrayList r11 = new java.util.ArrayList     // Catch: java.lang.Throwable -> L2f7
            r11.<init>()     // Catch: java.lang.Throwable -> L2f7
            java.util.Collection r11 = (java.util.Collection) r11     // Catch: java.lang.Throwable -> L2f7
            r12 = 0
            java.util.Iterator r13 = r9.iterator()     // Catch: java.lang.Throwable -> L2f7
        L72:
            boolean r9 = r13.hasNext()     // Catch: java.lang.Throwable -> L2f7
            if (r9 == 0) goto La3
            java.lang.Object r9 = r13.next()     // Catch: java.lang.Throwable -> L2f7
            r14 = r9
            com.xiaoduo.deskpet.chat.ChatMessage r14 = (com.xiaoduo.deskpet.chat.ChatMessage) r14     // Catch: java.lang.Throwable -> L2f7
            r15 = 0
            com.xiaoduo.deskpet.chat.ChatMessage$Role r5 = r14.getRole()     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatMessage$Role r6 = com.xiaoduo.deskpet.chat.ChatMessage.Role.SYSTEM     // Catch: java.lang.Throwable -> L2f7
            if (r5 == r6) goto L9b
            java.lang.String r5 = r14.getText()     // Catch: java.lang.Throwable -> L2f7
            java.lang.CharSequence r5 = (java.lang.CharSequence) r5     // Catch: java.lang.Throwable -> L2f7
            boolean r5 = kotlin.text.StringsKt.isBlank(r5)     // Catch: java.lang.Throwable -> L2f7
            if (r5 != 0) goto L96
            r5 = 1
            goto L97
        L96:
            r5 = 0
        L97:
            if (r5 == 0) goto L9b
            r5 = 1
            goto L9c
        L9b:
            r5 = 0
        L9c:
            if (r5 == 0) goto La1
            r11.add(r9)     // Catch: java.lang.Throwable -> L2f7
        La1:
            r6 = 1
            goto L72
        La3:
            r5 = r11
            java.util.List r5 = (java.util.List) r5     // Catch: java.lang.Throwable -> L2f7
            java.lang.Iterable r5 = (java.lang.Iterable) r5     // Catch: java.lang.Throwable -> L2f7
            r6 = 0
            java.util.ArrayList r9 = new java.util.ArrayList     // Catch: java.lang.Throwable -> L2f7
            r10 = 10
            int r10 = kotlin.collections.CollectionsKt.collectionSizeOrDefault(r5, r10)     // Catch: java.lang.Throwable -> L2f7
            r9.<init>(r10)     // Catch: java.lang.Throwable -> L2f7
            java.util.Collection r9 = (java.util.Collection) r9     // Catch: java.lang.Throwable -> L2f7
            r10 = 0
            java.util.Iterator r11 = r5.iterator()     // Catch: java.lang.Throwable -> L2f7
        Lbd:
            boolean r5 = r11.hasNext()     // Catch: java.lang.Throwable -> L2f7
            if (r5 == 0) goto Le0
            java.lang.Object r5 = r11.next()     // Catch: java.lang.Throwable -> L2f7
            r12 = r5
            com.xiaoduo.deskpet.chat.ChatMessage r12 = (com.xiaoduo.deskpet.chat.ChatMessage) r12     // Catch: java.lang.Throwable -> L2f7
            r13 = 0
            com.xiaoduo.deskpet.cloud.Message r14 = new com.xiaoduo.deskpet.cloud.Message     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatMessage$Role r15 = r12.getRole()     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r15 = r15.getWire()     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r7 = r12.getText()     // Catch: java.lang.Throwable -> L2f7
            r14.<init>(r15, r7)     // Catch: java.lang.Throwable -> L2f7
            r9.add(r14)     // Catch: java.lang.Throwable -> L2f7
            goto Lbd
        Le0:
            r5 = r9
            java.util.List r5 = (java.util.List) r5     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatViewModel r6 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.cloud.OpenAIChatClient r6 = com.xiaoduo.deskpet.chat.ChatViewModel.access$cloudClient(r6)     // Catch: java.lang.Throwable -> L2f7
            r7 = 0
            com.xiaoduo.deskpet.cloud.OpenAIChatClient$Result r6 = r6.chat(r5, r7, r8)     // Catch: java.lang.Throwable -> L2f7
            r5 = r6
            boolean r6 = r5 instanceof com.xiaoduo.deskpet.cloud.OpenAIChatClient.Result.Success     // Catch: java.lang.Throwable -> L2f7
            if (r6 == 0) goto L10f
            com.xiaoduo.deskpet.agui.AguiClient$ChatResult$Success r6 = new com.xiaoduo.deskpet.agui.AguiClient$ChatResult$Success     // Catch: java.lang.Throwable -> L2f7
            r7 = r5
            com.xiaoduo.deskpet.cloud.OpenAIChatClient$Result$Success r7 = (com.xiaoduo.deskpet.cloud.OpenAIChatClient.Result.Success) r7     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r7 = r7.getText()     // Catch: java.lang.Throwable -> L2f7
            r8 = r5
            com.xiaoduo.deskpet.cloud.OpenAIChatClient$Result$Success r8 = (com.xiaoduo.deskpet.cloud.OpenAIChatClient.Result.Success) r8     // Catch: java.lang.Throwable -> L2f7
            java.lang.Long r8 = r8.getFirstTokenLatencyMs()     // Catch: java.lang.Throwable -> L2f7
            r9 = 1
            r6.<init>(r7, r8, r9)     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.agui.AguiClient$ChatResult r6 = (com.xiaoduo.deskpet.agui.AguiClient.ChatResult) r6     // Catch: java.lang.Throwable -> L2f7
            goto L14b
        L10f:
            boolean r6 = r5 instanceof com.xiaoduo.deskpet.cloud.OpenAIChatClient.Result.Error     // Catch: java.lang.Throwable -> L2f7
            if (r6 == 0) goto L122
            com.xiaoduo.deskpet.agui.AguiClient$ChatResult$Error r6 = new com.xiaoduo.deskpet.agui.AguiClient$ChatResult$Error     // Catch: java.lang.Throwable -> L2f7
            r7 = r5
            com.xiaoduo.deskpet.cloud.OpenAIChatClient$Result$Error r7 = (com.xiaoduo.deskpet.cloud.OpenAIChatClient.Result.Error) r7     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r7 = r7.getMessage()     // Catch: java.lang.Throwable -> L2f7
            r6.<init>(r7)     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.agui.AguiClient$ChatResult r6 = (com.xiaoduo.deskpet.agui.AguiClient.ChatResult) r6     // Catch: java.lang.Throwable -> L2f7
            goto L14b
        L122:
            kotlin.NoWhenBranchMatchedException r0 = new kotlin.NoWhenBranchMatchedException     // Catch: java.lang.Throwable -> L2f7
            r0.<init>()     // Catch: java.lang.Throwable -> L2f7
            throw r0     // Catch: java.lang.Throwable -> L2f7
        L128:
            com.xiaoduo.deskpet.chat.ChatViewModel r5 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.agui.AguiClient r18 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getClient$p(r5)     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r5 = r3.$userText     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r6 = r3.$wireId     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatSession r7 = r3.$session     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r21 = r7.getId()     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r7 = r3.$threadName     // Catch: java.lang.Throwable -> L2f7
            java.util.List<kotlin.Pair<java.lang.String, java.lang.String>> r9 = r3.$fileRefs     // Catch: java.lang.Throwable -> L2f7
            r19 = r5
            r20 = r6
            r22 = r7
            r23 = r9
            r24 = r8
            com.xiaoduo.deskpet.agui.AguiClient$ChatResult r6 = r18.chat(r19, r20, r21, r22, r23, r24)     // Catch: java.lang.Throwable -> L2f7
        L14b:
            r5 = r6
            int r6 = r3.$myEpoch     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatViewModel r7 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            int r7 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getTurnEpoch$p(r7)     // Catch: java.lang.Throwable -> L2f7
            if (r6 == r7) goto L16e
            kotlin.Unit r0 = kotlin.Unit.INSTANCE     // Catch: java.lang.Throwable -> L2f7
            int r1 = r3.$myEpoch
            com.xiaoduo.deskpet.chat.ChatViewModel r5 = r3.this$0
            int r5 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getTurnEpoch$p(r5)
            if (r1 != r5) goto L16d
            com.xiaoduo.deskpet.chat.ChatViewModel r1 = r3.this$0
            r5 = 0
            com.xiaoduo.deskpet.chat.ChatViewModel.access$setActiveAssistantId$p(r1, r5)
            com.xiaoduo.deskpet.chat.ChatViewModel r1 = r3.this$0
            com.xiaoduo.deskpet.chat.ChatViewModel.access$setChatJob$p(r1, r5)
        L16d:
            return r0
        L16e:
            boolean r6 = r5 instanceof com.xiaoduo.deskpet.agui.AguiClient.ChatResult.Success     // Catch: java.lang.Throwable -> L2f7
            if (r6 == 0) goto L292
            com.xiaoduo.deskpet.chat.ChatViewModel r6 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            kotlinx.coroutines.flow.MutableStateFlow r6 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_messages$p(r6)     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatViewModel r7 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatTurnLogic r7 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getTurns$p(r7)     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatViewModel r8 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            kotlinx.coroutines.flow.MutableStateFlow r8 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_messages$p(r8)     // Catch: java.lang.Throwable -> L2f7
            java.lang.Object r8 = r8.getValue()     // Catch: java.lang.Throwable -> L2f7
            java.util.List r8 = (java.util.List) r8     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r9 = r3.$assistantId     // Catch: java.lang.Throwable -> L2f7
            r10 = r5
            com.xiaoduo.deskpet.agui.AguiClient$ChatResult$Success r10 = (com.xiaoduo.deskpet.agui.AguiClient.ChatResult.Success) r10     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r10 = r10.getText()     // Catch: java.lang.Throwable -> L2f7
            java.util.List r7 = r7.markDone(r8, r9, r10)     // Catch: java.lang.Throwable -> L2f7
            r6.setValue(r7)     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatViewModel r6 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            kotlinx.coroutines.flow.MutableStateFlow r6 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_messages$p(r6)     // Catch: java.lang.Throwable -> L2f7
            java.lang.Object r6 = r6.getValue()     // Catch: java.lang.Throwable -> L2f7
            java.lang.Iterable r6 = (java.lang.Iterable) r6     // Catch: java.lang.Throwable -> L2f7
            java.lang.String r7 = r3.$assistantId     // Catch: java.lang.Throwable -> L2f7
            r8 = 0
            java.util.Iterator r9 = r6.iterator()     // Catch: java.lang.Throwable -> L2f7
        L1ae:
            boolean r6 = r9.hasNext()     // Catch: java.lang.Throwable -> L2f7
            if (r6 == 0) goto L1c7
            java.lang.Object r6 = r9.next()     // Catch: java.lang.Throwable -> L2f7
            r10 = r6
            com.xiaoduo.deskpet.chat.ChatMessage r10 = (com.xiaoduo.deskpet.chat.ChatMessage) r10     // Catch: java.lang.Throwable -> L2f7
            r11 = 0
            java.lang.String r12 = r10.getId()     // Catch: java.lang.Throwable -> L2f7
            boolean r12 = kotlin.jvm.internal.Intrinsics.areEqual(r12, r7)     // Catch: java.lang.Throwable -> L2f7
            if (r12 == 0) goto L1ae
            goto L1c8
        L1c7:
            r6 = 0
        L1c8:
            com.xiaoduo.deskpet.chat.ChatMessage r6 = (com.xiaoduo.deskpet.chat.ChatMessage) r6     // Catch: java.lang.Throwable -> L2f7
            if (r6 == 0) goto L1d1
            java.lang.String r6 = r6.getText()     // Catch: java.lang.Throwable -> L2f7
            goto L1d2
        L1d1:
            r6 = 0
        L1d2:
            if (r6 != 0) goto L1d5
            goto L1d6
        L1d5:
            r0 = r6
        L1d6:
            com.xiaoduo.deskpet.chat.ChatViewModel r6 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatSession r7 = r3.$session     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatViewModel.access$persist(r6, r7)     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatViewModel r6 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            kotlinx.coroutines.flow.MutableStateFlow r6 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_phase$p(r6)     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatViewModel$Phase r7 = com.xiaoduo.deskpet.chat.ChatViewModel.Phase.IDLE     // Catch: java.lang.Throwable -> L2f7
            r6.setValue(r7)     // Catch: java.lang.Throwable -> L2f7
            java.util.Set<java.lang.String> r6 = r3.$attachedLocalIds     // Catch: java.lang.Throwable -> L2f7
            java.util.Collection r6 = (java.util.Collection) r6     // Catch: java.lang.Throwable -> L2f7
            boolean r6 = r6.isEmpty()     // Catch: java.lang.Throwable -> L2f7
            r7 = 1
            r6 = r6 ^ r7
            if (r6 == 0) goto L244
            com.xiaoduo.deskpet.chat.ChatViewModel r6 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            kotlinx.coroutines.flow.MutableStateFlow r6 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_files$p(r6)     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.FileListRules r7 = com.xiaoduo.deskpet.chat.FileListRules.INSTANCE     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatViewModel r8 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            kotlinx.coroutines.flow.MutableStateFlow r8 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_files$p(r8)     // Catch: java.lang.Throwable -> L2f7
            java.lang.Object r8 = r8.getValue()     // Catch: java.lang.Throwable -> L2f7
            java.util.List r8 = (java.util.List) r8     // Catch: java.lang.Throwable -> L2f7
            java.util.Set<java.lang.String> r9 = r3.$attachedLocalIds     // Catch: java.lang.Throwable -> L2f7
            java.util.List r7 = r7.clearAttached(r8, r9)     // Catch: java.lang.Throwable -> L2f7
            r6.setValue(r7)     // Catch: java.lang.Throwable -> L2f7
            java.util.Set<java.lang.String> r6 = r3.$attachedLocalIds     // Catch: java.lang.Throwable -> L2f7
            java.lang.Iterable r6 = (java.lang.Iterable) r6     // Catch: java.lang.Throwable -> L2f7
            com.xiaoduo.deskpet.chat.ChatViewModel r7 = r3.this$0     // Catch: java.lang.Throwable -> L2f7
            r8 = 0
            java.util.Iterator r9 = r6.iterator()     // Catch: java.lang.Throwable -> L2f7
        L21c:
            boolean r6 = r9.hasNext()     // Catch: java.lang.Throwable -> L2f7
            if (r6 == 0) goto L242
            java.lang.Object r6 = r9.next()     // Catch: java.lang.Throwable -> L2f7
            r10 = r6
            java.lang.String r10 = (java.lang.String) r10     // Catch: java.lang.Throwable -> L2f7
            r6 = r10
            r10 = 0
            java.util.HashMap r11 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getUploadJobs$p(r7)     // Catch: java.lang.Throwable -> L2f7
            java.lang.Object r11 = r11.remove(r6)     // Catch: java.lang.Throwable -> L2f7
            kotlinx.coroutines.Job r11 = (kotlinx.coroutines.Job) r11     // Catch: java.lang.Throwable -> L2f7
            if (r11 == 0) goto L240
            kotlin.jvm.internal.Intrinsics.checkNotNull(r11)     // Catch: java.lang.Throwable -> L2f7
            r12 = 1
            r13 = 0
            kotlinx.coroutines.Job.DefaultImpls.cancel$default(r11, r13, r12, r13)     // Catch: java.lang.Throwable -> L2f5
            goto L241
        L240:
            r12 = 1
        L241:
            goto L21c
        L242:
            r12 = 1
            goto L245
        L244:
            r12 = 1
        L245:
            com.xiaoduo.deskpet.util.PetLogger r6 = com.xiaoduo.deskpet.util.PetLogger.INSTANCE     // Catch: java.lang.Throwable -> L2f5
            int r7 = r0.length()     // Catch: java.lang.Throwable -> L2f5
            r0 = r5
            com.xiaoduo.deskpet.agui.AguiClient$ChatResult$Success r0 = (com.xiaoduo.deskpet.agui.AguiClient.ChatResult.Success) r0     // Catch: java.lang.Throwable -> L2f5
            java.lang.Long r0 = r0.getFirstTokenLatencyMs()     // Catch: java.lang.Throwable -> L2f5
            r8 = r5
            com.xiaoduo.deskpet.agui.AguiClient$ChatResult$Success r8 = (com.xiaoduo.deskpet.agui.AguiClient.ChatResult.Success) r8     // Catch: java.lang.Throwable -> L2f5
            int r8 = r8.getAttempts()     // Catch: java.lang.Throwable -> L2f5
            java.lang.StringBuilder r9 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L2f5
            r9.<init>()     // Catch: java.lang.Throwable -> L2f5
            java.lang.String r10 = "chat round done: reply "
            java.lang.StringBuilder r9 = r9.append(r10)     // Catch: java.lang.Throwable -> L2f5
            java.lang.StringBuilder r7 = r9.append(r7)     // Catch: java.lang.Throwable -> L2f5
            java.lang.String r9 = " chars, firstToken="
            java.lang.StringBuilder r7 = r7.append(r9)     // Catch: java.lang.Throwable -> L2f5
            java.lang.StringBuilder r0 = r7.append(r0)     // Catch: java.lang.Throwable -> L2f5
            java.lang.String r7 = "ms, attempts="
            java.lang.StringBuilder r0 = r0.append(r7)     // Catch: java.lang.Throwable -> L2f5
            java.lang.StringBuilder r0 = r0.append(r8)     // Catch: java.lang.Throwable -> L2f5
            java.lang.String r0 = r0.toString()     // Catch: java.lang.Throwable -> L2f5
            r6.i(r1, r0)     // Catch: java.lang.Throwable -> L2f5
            com.xiaoduo.deskpet.PetOverlayService r0 = r3.$svc     // Catch: java.lang.Throwable -> L2f5
            if (r0 == 0) goto L2e9
            com.xiaoduo.deskpet.ui.overlay.BubbleOwner r5 = com.xiaoduo.deskpet.ui.overlay.BubbleOwner.CHAT     // Catch: java.lang.Throwable -> L2f5
            int r6 = r3.$myRound     // Catch: java.lang.Throwable -> L2f5
            r7 = 12000(0x2ee0, double:5.929E-320)
            r0.hideBubbleLater(r5, r6, r7)     // Catch: java.lang.Throwable -> L2f5
            goto L2e9
        L292:
            r12 = 1
            boolean r0 = r5 instanceof com.xiaoduo.deskpet.agui.AguiClient.ChatResult.Error     // Catch: java.lang.Throwable -> L2f5
            if (r0 == 0) goto L2e9
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0     // Catch: java.lang.Throwable -> L2f5
            kotlinx.coroutines.flow.MutableStateFlow r0 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_messages$p(r0)     // Catch: java.lang.Throwable -> L2f5
            com.xiaoduo.deskpet.chat.ChatViewModel r6 = r3.this$0     // Catch: java.lang.Throwable -> L2f5
            com.xiaoduo.deskpet.chat.ChatTurnLogic r6 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getTurns$p(r6)     // Catch: java.lang.Throwable -> L2f5
            com.xiaoduo.deskpet.chat.ChatViewModel r7 = r3.this$0     // Catch: java.lang.Throwable -> L2f5
            kotlinx.coroutines.flow.MutableStateFlow r7 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_messages$p(r7)     // Catch: java.lang.Throwable -> L2f5
            java.lang.Object r7 = r7.getValue()     // Catch: java.lang.Throwable -> L2f5
            java.util.List r7 = (java.util.List) r7     // Catch: java.lang.Throwable -> L2f5
            java.lang.String r8 = r3.$assistantId     // Catch: java.lang.Throwable -> L2f5
            java.util.List r6 = r6.markError(r7, r8)     // Catch: java.lang.Throwable -> L2f5
            r0.setValue(r6)     // Catch: java.lang.Throwable -> L2f5
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0     // Catch: java.lang.Throwable -> L2f5
            com.xiaoduo.deskpet.chat.ChatSession r6 = r3.$session     // Catch: java.lang.Throwable -> L2f5
            com.xiaoduo.deskpet.chat.ChatViewModel.access$persist(r0, r6)     // Catch: java.lang.Throwable -> L2f5
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0     // Catch: java.lang.Throwable -> L2f5
            kotlinx.coroutines.flow.MutableStateFlow r0 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_phase$p(r0)     // Catch: java.lang.Throwable -> L2f5
            com.xiaoduo.deskpet.chat.ChatViewModel$Phase r6 = com.xiaoduo.deskpet.chat.ChatViewModel.Phase.ERROR     // Catch: java.lang.Throwable -> L2f5
            r0.setValue(r6)     // Catch: java.lang.Throwable -> L2f5
            com.xiaoduo.deskpet.util.PetLogger r0 = com.xiaoduo.deskpet.util.PetLogger.INSTANCE     // Catch: java.lang.Throwable -> L2f5
            r6 = r5
            com.xiaoduo.deskpet.agui.AguiClient$ChatResult$Error r6 = (com.xiaoduo.deskpet.agui.AguiClient.ChatResult.Error) r6     // Catch: java.lang.Throwable -> L2f5
            java.lang.String r6 = r6.getMessage()     // Catch: java.lang.Throwable -> L2f5
            java.lang.StringBuilder r7 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L2f5
            r7.<init>()     // Catch: java.lang.Throwable -> L2f5
            java.lang.String r8 = "chat round failed: "
            java.lang.StringBuilder r7 = r7.append(r8)     // Catch: java.lang.Throwable -> L2f5
            java.lang.StringBuilder r6 = r7.append(r6)     // Catch: java.lang.Throwable -> L2f5
            java.lang.String r6 = r6.toString()     // Catch: java.lang.Throwable -> L2f5
            r0.w(r1, r6)     // Catch: java.lang.Throwable -> L2f5
        L2e9:
            int r0 = r3.$myEpoch
            com.xiaoduo.deskpet.chat.ChatViewModel r1 = r3.this$0
            int r1 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getTurnEpoch$p(r1)
            if (r0 != r1) goto L3c9
            goto L3be
        L2f5:
            r0 = move-exception
            goto L2fc
        L2f7:
            r0 = move-exception
            r12 = 1
            goto L2fc
        L2fa:
            r0 = move-exception
            r12 = r6
        L2fc:
            boolean r5 = r0 instanceof java.util.concurrent.CancellationException     // Catch: java.lang.Throwable -> L3ce
            if (r5 != 0) goto L3cd
            int r5 = r3.$myEpoch     // Catch: java.lang.Throwable -> L3ce
            com.xiaoduo.deskpet.chat.ChatViewModel r6 = r3.this$0     // Catch: java.lang.Throwable -> L3ce
            int r6 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getTurnEpoch$p(r6)     // Catch: java.lang.Throwable -> L3ce
            if (r5 == r6) goto L322
            kotlin.Unit r1 = kotlin.Unit.INSTANCE     // Catch: java.lang.Throwable -> L3ce
            int r0 = r3.$myEpoch
            com.xiaoduo.deskpet.chat.ChatViewModel r5 = r3.this$0
            int r5 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getTurnEpoch$p(r5)
            if (r0 != r5) goto L321
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0
            r5 = 0
            com.xiaoduo.deskpet.chat.ChatViewModel.access$setActiveAssistantId$p(r0, r5)
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0
            com.xiaoduo.deskpet.chat.ChatViewModel.access$setChatJob$p(r0, r5)
        L321:
            return r1
        L322:
            com.xiaoduo.deskpet.util.PetLogger r5 = com.xiaoduo.deskpet.util.PetLogger.INSTANCE     // Catch: java.lang.Throwable -> L3ce
            java.lang.String r6 = r0.getMessage()     // Catch: java.lang.Throwable -> L3ce
            java.lang.StringBuilder r7 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L3ce
            r7.<init>()     // Catch: java.lang.Throwable -> L3ce
            java.lang.String r8 = "chat round crashed: "
            java.lang.StringBuilder r7 = r7.append(r8)     // Catch: java.lang.Throwable -> L3ce
            java.lang.StringBuilder r6 = r7.append(r6)     // Catch: java.lang.Throwable -> L3ce
            java.lang.String r6 = r6.toString()     // Catch: java.lang.Throwable -> L3ce
            r5.e(r1, r6, r0)     // Catch: java.lang.Throwable -> L3ce
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0     // Catch: java.lang.Throwable -> L3ce
            kotlinx.coroutines.flow.MutableStateFlow r0 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_messages$p(r0)     // Catch: java.lang.Throwable -> L3ce
            java.lang.Object r0 = r0.getValue()     // Catch: java.lang.Throwable -> L3ce
            java.lang.Iterable r0 = (java.lang.Iterable) r0     // Catch: java.lang.Throwable -> L3ce
            java.lang.String r1 = r3.$assistantId     // Catch: java.lang.Throwable -> L3ce
            r5 = 0
            java.util.Iterator r6 = r0.iterator()     // Catch: java.lang.Throwable -> L3ce
        L352:
            boolean r0 = r6.hasNext()     // Catch: java.lang.Throwable -> L3ce
            if (r0 == 0) goto L36b
            java.lang.Object r0 = r6.next()     // Catch: java.lang.Throwable -> L3ce
            r7 = r0
            com.xiaoduo.deskpet.chat.ChatMessage r7 = (com.xiaoduo.deskpet.chat.ChatMessage) r7     // Catch: java.lang.Throwable -> L3ce
            r8 = 0
            java.lang.String r9 = r7.getId()     // Catch: java.lang.Throwable -> L3ce
            boolean r9 = kotlin.jvm.internal.Intrinsics.areEqual(r9, r1)     // Catch: java.lang.Throwable -> L3ce
            if (r9 == 0) goto L352
            goto L36c
        L36b:
            r0 = 0
        L36c:
            com.xiaoduo.deskpet.chat.ChatMessage r0 = (com.xiaoduo.deskpet.chat.ChatMessage) r0     // Catch: java.lang.Throwable -> L3ce
            if (r0 == 0) goto L376
        L371:
            com.xiaoduo.deskpet.chat.ChatMessage$Status r0 = r0.getStatus()     // Catch: java.lang.Throwable -> L3ce
            goto L377
        L376:
            r0 = 0
        L377:
            com.xiaoduo.deskpet.chat.ChatMessage$Status r1 = com.xiaoduo.deskpet.chat.ChatMessage.Status.DONE     // Catch: java.lang.Throwable -> L3ce
            if (r0 != r1) goto L37d
            r5 = r12
            goto L37e
        L37d:
            r5 = 0
        L37e:
            r0 = r5
            if (r0 != 0) goto L3b4
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0     // Catch: java.lang.Throwable -> L3ce
            kotlinx.coroutines.flow.MutableStateFlow r0 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_messages$p(r0)     // Catch: java.lang.Throwable -> L3ce
            com.xiaoduo.deskpet.chat.ChatViewModel r1 = r3.this$0     // Catch: java.lang.Throwable -> L3ce
            com.xiaoduo.deskpet.chat.ChatTurnLogic r1 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getTurns$p(r1)     // Catch: java.lang.Throwable -> L3ce
            com.xiaoduo.deskpet.chat.ChatViewModel r5 = r3.this$0     // Catch: java.lang.Throwable -> L3ce
            kotlinx.coroutines.flow.MutableStateFlow r5 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_messages$p(r5)     // Catch: java.lang.Throwable -> L3ce
            java.lang.Object r5 = r5.getValue()     // Catch: java.lang.Throwable -> L3ce
            java.util.List r5 = (java.util.List) r5     // Catch: java.lang.Throwable -> L3ce
            java.lang.String r6 = r3.$assistantId     // Catch: java.lang.Throwable -> L3ce
            java.util.List r1 = r1.markError(r5, r6)     // Catch: java.lang.Throwable -> L3ce
            r0.setValue(r1)     // Catch: java.lang.Throwable -> L3ce
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0     // Catch: java.lang.Throwable -> L3ce
            com.xiaoduo.deskpet.chat.ChatSession r1 = r3.$session     // Catch: java.lang.Throwable -> L3ce
            com.xiaoduo.deskpet.chat.ChatViewModel.access$persist(r0, r1)     // Catch: java.lang.Throwable -> L3ce
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0     // Catch: java.lang.Throwable -> L3ce
            kotlinx.coroutines.flow.MutableStateFlow r0 = com.xiaoduo.deskpet.chat.ChatViewModel.access$get_phase$p(r0)     // Catch: java.lang.Throwable -> L3ce
            com.xiaoduo.deskpet.chat.ChatViewModel$Phase r1 = com.xiaoduo.deskpet.chat.ChatViewModel.Phase.ERROR     // Catch: java.lang.Throwable -> L3ce
            r0.setValue(r1)     // Catch: java.lang.Throwable -> L3ce
        L3b4:
            int r0 = r3.$myEpoch
            com.xiaoduo.deskpet.chat.ChatViewModel r1 = r3.this$0
            int r1 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getTurnEpoch$p(r1)
            if (r0 != r1) goto L3c9
        L3be:
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0
            r1 = 0
            com.xiaoduo.deskpet.chat.ChatViewModel.access$setActiveAssistantId$p(r0, r1)
            com.xiaoduo.deskpet.chat.ChatViewModel r0 = r3.this$0
            com.xiaoduo.deskpet.chat.ChatViewModel.access$setChatJob$p(r0, r1)
        L3c9:
            kotlin.Unit r0 = kotlin.Unit.INSTANCE
            return r0
        L3cd:
            throw r0     // Catch: java.lang.Throwable -> L3ce
        L3ce:
            r0 = move-exception
            int r1 = r3.$myEpoch
            com.xiaoduo.deskpet.chat.ChatViewModel r5 = r3.this$0
            int r5 = com.xiaoduo.deskpet.chat.ChatViewModel.access$getTurnEpoch$p(r5)
            if (r1 != r5) goto L3e4
            com.xiaoduo.deskpet.chat.ChatViewModel r1 = r3.this$0
            r5 = 0
            com.xiaoduo.deskpet.chat.ChatViewModel.access$setActiveAssistantId$p(r1, r5)
            com.xiaoduo.deskpet.chat.ChatViewModel r1 = r3.this$0
            com.xiaoduo.deskpet.chat.ChatViewModel.access$setChatJob$p(r1, r5)
        L3e4:
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaoduo.deskpet.chat.ChatViewModel$runChat$1.invokeSuspend(java.lang.Object):java.lang.Object");
    }
}
