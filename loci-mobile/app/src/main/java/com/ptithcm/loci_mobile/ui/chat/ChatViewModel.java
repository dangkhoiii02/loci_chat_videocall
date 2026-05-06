package com.ptithcm.loci_mobile.ui.chat;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ptithcm.loci_mobile.data.auth.TokenManager;
import com.ptithcm.loci_mobile.data.model.chat.ChatMessage;
import com.ptithcm.loci_mobile.data.model.chat.LocalMessage;
import com.ptithcm.loci_mobile.data.model.chat.MessageList;
import com.ptithcm.loci_mobile.data.repository.MessageRepository;
import com.ptithcm.loci_mobile.util.Result;
import com.ptithcm.loci_mobile.util.SingleLiveEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatViewModel extends AndroidViewModel {

    private static final int PAGE_SIZE = 20;

    private final MessageRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Mutable backing list — always accessed on main thread via postValue
    private final List<LocalMessage> messageList = new ArrayList<>();
    // Server messageIds we've already added — prevents duplicates
    private final Set<String> knownServerIds = new HashSet<>();

    private final MutableLiveData<List<LocalMessage>> messages = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoadingInitial = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isLoadingOlder   = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> hasMoreMessages  = new MutableLiveData<>(true);
    private final SingleLiveEvent<String>  errorEvent       = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void>    scrollToBottom   = new SingleLiveEvent<>();

    private String conversationId;
    private String currentUserId;
    private boolean isGroupConversation;

    // Polling for new messages every 5s (until WebSocket is implemented)
    private final java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private java.util.concurrent.ScheduledFuture<?> pollFuture;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        repository    = new MessageRepository(application);
        currentUserId = resolveCurrentUserId(application);
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    public void init(String conversationId) {
        init(conversationId, false);
    }

    public void init(String conversationId, boolean isGroupConversation) {
        this.conversationId = conversationId;
        this.isGroupConversation = isGroupConversation;
        loadInitialMessages();
        startPolling();
    }

    /** Poll for new messages every 5 seconds. */
    private void startPolling() {
        if (pollFuture != null && !pollFuture.isCancelled()) return;
        pollFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (conversationId == null) return;
            // Fetch latest page and merge new messages silently
            Result<MessageList> result = repository.getMessages(conversationId, PAGE_SIZE, null);
            if (!result.isSuccess() || result.getData() == null) return;
            List<LocalMessage> incoming = new ArrayList<>();
            if (result.getData().getMessages() != null) {
                for (ChatMessage msg : result.getData().getMessages()) {
                    if (msg.getMessageId() != null && !knownServerIds.contains(msg.getMessageId())) {
                        incoming.add(LocalMessage.fromServer(msg, currentUserId));
                    }
                }
            }
            if (incoming.isEmpty()) return;
            boolean hasNew = false;
            synchronized (messageList) {
                for (LocalMessage m : incoming) {
                    if (m.getMessageId() != null && !knownServerIds.contains(m.getMessageId())) {
                        knownServerIds.add(m.getMessageId());
                        messageList.add(m);
                        hasNew = true;
                    }
                }
            }
            if (hasNew) {
                publishMessages();
                scrollToBottom.postValue(null);
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public LiveData<List<LocalMessage>> getMessages()       { return messages; }
    public LiveData<Boolean> getIsLoadingInitial()          { return isLoadingInitial; }
    public LiveData<Boolean> getIsLoadingOlder()            { return isLoadingOlder; }
    public LiveData<Boolean> getHasMoreMessages()           { return hasMoreMessages; }
    public LiveData<String>  getErrorEvent()                { return errorEvent; }
    public LiveData<Void>    getScrollToBottom()            { return scrollToBottom; }

    // ── Load messages ─────────────────────────────────────────────────────────

    public void loadInitialMessages() {
        isLoadingInitial.setValue(true);
        executor.execute(() -> {
            Result<MessageList> result = repository.getMessages(conversationId, PAGE_SIZE, null);
            isLoadingInitial.postValue(false);
            if (result.isSuccess()) {
                List<LocalMessage> loaded = convertAndDeduplicate(result.getData());
                synchronized (messageList) {
                    messageList.clear();
                    knownServerIds.clear();
                    for (LocalMessage m : loaded) {
                        if (m.getMessageId() != null) knownServerIds.add(m.getMessageId());
                    }
                    messageList.addAll(loaded);
                }
                publishMessages();
                scrollToBottom.postValue(null);
            } else {
                errorEvent.postValue(result.getMessage());
            }
        });
    }

    public void loadOlderMessages() {
        Boolean loading = isLoadingOlder.getValue();
        Boolean hasMore = hasMoreMessages.getValue();
        if (Boolean.TRUE.equals(loading) || Boolean.FALSE.equals(hasMore)) return;

        String cursor;
        synchronized (messageList) {
            if (messageList.isEmpty()) return;
            // Find the oldest server message (first in list that has a real messageId)
            cursor = null;
            for (LocalMessage m : messageList) {
                if (m.getMessageId() != null) { cursor = m.getMessageId(); break; }
            }
        }
        if (cursor == null) return;

        isLoadingOlder.setValue(true);
        final String finalCursor = cursor;
        executor.execute(() -> {
            Result<MessageList> result = repository.getMessages(conversationId, PAGE_SIZE, finalCursor);
            isLoadingOlder.postValue(false);
            if (result.isSuccess()) {
                hasMoreMessages.postValue(result.getData().isHasMore());
                List<LocalMessage> older = convertAndDeduplicate(result.getData());
                synchronized (messageList) {
                    for (LocalMessage m : older) {
                        if (m.getMessageId() != null && !knownServerIds.contains(m.getMessageId())) {
                            knownServerIds.add(m.getMessageId());
                            messageList.add(0, m); // prepend
                        }
                    }
                }
                publishMessages();
            } else {
                errorEvent.postValue(result.getMessage());
            }
        });
    }

    // ── Send message ──────────────────────────────────────────────────────────

    public void sendMessage(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return;

        // 1. Create local pending message and show immediately
        LocalMessage local = LocalMessage.outgoing(conversationId, trimmed, "text", currentUserId);
        synchronized (messageList) {
            messageList.add(local);
        }
        publishMessages();
        scrollToBottom.postValue(null);

        // 2. Call API in background
        executor.execute(() -> doSend(local, trimmed));
    }

    /** Retry a previously failed message. */
    public void retryMessage(String localId) {
        LocalMessage target = findByLocalId(localId);
        if (target == null || !target.isFailed()) return;
        target.markPending();
        publishMessages();
        executor.execute(() -> doSend(target, target.getContent()));
    }

    private void doSend(LocalMessage local, String content) {
        Result<ChatMessage> result = isGroupConversation
                ? repository.sendGroupMessage(conversationId, content, "text")
                : repository.sendDirectMessage(conversationId, content, "text");
        synchronized (messageList) {
            if (result.isSuccess()) {
                ChatMessage serverMsg = result.getData();
                // Deduplicate: if server messageId already in list, remove the local copy
                if (serverMsg.getMessageId() != null &&
                    knownServerIds.contains(serverMsg.getMessageId())) {
                    messageList.remove(local);
                } else {
                    local.confirmSent(serverMsg);
                    if (serverMsg.getMessageId() != null) {
                        knownServerIds.add(serverMsg.getMessageId());
                    }
                }
            } else {
                local.markFailed();
            }
        }
        publishMessages();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<LocalMessage> convertAndDeduplicate(MessageList data) {
        List<LocalMessage> result = new ArrayList<>();
        if (data.getMessages() == null) return result;
        for (ChatMessage msg : data.getMessages()) {
            if (msg.getMessageId() != null && knownServerIds.contains(msg.getMessageId())) {
                continue; // already in list
            }
            result.add(LocalMessage.fromServer(msg, currentUserId));
        }
        return result;
    }

    private LocalMessage findByLocalId(String localId) {
        synchronized (messageList) {
            for (LocalMessage m : messageList) {
                if (m.getLocalId().equals(localId)) return m;
            }
        }
        return null;
    }

    private void publishMessages() {
        List<LocalMessage> snapshot;
        synchronized (messageList) {
            snapshot = new ArrayList<>(messageList);
        }
        messages.postValue(snapshot);
    }

    private String resolveCurrentUserId(Application app) {
        // TokenManager stores the access token; we can't decode JWT here without a library.
        // We'll set it lazily from the profile once loaded.
        // For now return a placeholder — ProfileViewModel will call setCurrentUserId().
        return null;
    }

    /** Called by the host activity/fragment once the profile is loaded. */
    public void setCurrentUserId(String userId) {
        this.currentUserId = userId;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (pollFuture != null) pollFuture.cancel(false);
        scheduler.shutdown();
        executor.shutdown();
    }
}
