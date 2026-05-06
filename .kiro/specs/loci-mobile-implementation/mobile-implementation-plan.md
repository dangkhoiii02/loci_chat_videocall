# Loci Mobile Implementation Plan for Kiro

## Goal

Build the Android mobile application in `loci-mobile` so it can connect to the existing Loci backend, authenticate with Keycloak, display chat/contact/profile features, receive real-time WebSocket/STOMP events, upload and display media through the backend/MinIO flow, and support a stable local development loop from Android Studio.

The current mobile project is a minimal Android app using Java and XML:

- Project path: `loci-mobile`
- Main module: `loci-mobile/app`
- Package: `com.ptithcm.loci_mobile`
- Entry point: `app/src/main/java/com/ptithcm/loci_mobile/MainActivity.java`
- Layout: `app/src/main/res/layout/activity_main.xml`

Use Java + XML + a lightweight MVVM/repository structure unless the user explicitly asks to migrate to Kotlin/Compose.

## Local Server Targets

Use these URLs when running from Android Emulator:

```text
BASE_API_URL=http://10.0.2.2:8080/api/v1
KEYCLOAK_URL=http://10.0.2.2:9093
WEBSOCKET_URL=ws://10.0.2.2:8080/api/v1/ws
MINIO_PUBLIC_URL=http://10.0.2.2:9000
LIVEKIT_URL=ws://10.0.2.2:7880
```

Use these URLs when running from a real Android device on the same Wi-Fi, replacing `<MAC_LAN_IP>` with the Mac IP:

```text
BASE_API_URL=http://<MAC_LAN_IP>:8080/api/v1
KEYCLOAK_URL=http://<MAC_LAN_IP>:9093
WEBSOCKET_URL=ws://<MAC_LAN_IP>:8080/api/v1/ws
MINIO_PUBLIC_URL=http://<MAC_LAN_IP>:9000
LIVEKIT_URL=ws://<MAC_LAN_IP>:7880
```

The backend `.env` must use matching public URLs for mobile-facing services:

```env
KEYCLOAK_ISSUER_URI_HOST=http://10.0.2.2:9093
KEYCLOAK_JWK_SET_URI_HOST=http://localhost:9093
UPLOAD_MINIO_URL_PREFIX=http://10.0.2.2:9000
LIVEKIT_URL=ws://10.0.2.2:7880
```

For a real device, replace `10.0.2.2` with the Mac LAN IP where appropriate.

## Non-Negotiable Android Setup

Update `AndroidManifest.xml` before implementing network calls:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Inside `<application>`, allow local HTTP during development:

```xml
android:usesCleartextTraffic="true"
```

If a stricter setup is preferred later, replace broad cleartext with a network security config allowing only `10.0.2.2`, localhost, and the Mac LAN IP.

## Recommended Dependencies

Add dependencies through `loci-mobile/gradle/libs.versions.toml` and `loci-mobile/app/build.gradle`.

Required:

- Retrofit: REST API client.
- OkHttp: HTTP client, auth interceptor, logging interceptor.
- Gson or Moshi: JSON serialization.
- Lifecycle ViewModel + LiveData: MVVM state.
- RecyclerView: chat list, messages, contacts.
- Glide: avatar/media loading.
- SwipeRefreshLayout: refresh lists.
- Material Components: UI controls already present.

Recommended after REST is stable:

- STOMP client for Android, or OkHttp WebSocket plus STOMP frame handling if the library causes compatibility issues.
- Room for local cache and pending message queue.
- WorkManager for retrying unsent messages/upload tasks.
- EncryptedSharedPreferences for token storage.

## Target Package Structure

Create this structure under:

```text
loci-mobile/app/src/main/java/com/ptithcm/loci_mobile/
```

```text
config/
  AppConfig.java

data/
  api/
    ApiClient.java
    AuthApi.java
    UserApi.java
    ContactApi.java
    ConversationApi.java
    MessageApi.java
    GroupApi.java
    NotificationApi.java
    UploadApi.java
  auth/
    TokenManager.java
    AuthInterceptor.java
    TokenAuthenticator.java
    AuthRepository.java
  model/
    auth/
    user/
    contact/
    chat/
    group/
    notification/
    common/
  repository/
    UserRepository.java
    ContactRepository.java
    ConversationRepository.java
    MessageRepository.java
    GroupRepository.java
    NotificationRepository.java
  socket/
    StompClientManager.java
    SocketEventRouter.java
    SocketSubscriptions.java
    SocketState.java

ui/
  auth/
  main/
  chatlist/
  chat/
  contacts/
  profile/
  group/
  notifications/
  common/

util/
  Result.java
  UiState.java
  DateTimeFormatter.java
  NetworkUtil.java
  SingleLiveEvent.java
```

Keep responsibilities clean:

- `api`: Retrofit interfaces only.
- `auth`: token acquisition, persistence, automatic header injection.
- `repository`: app-facing data operations and error normalization.
- `socket`: WebSocket/STOMP connection, subscriptions, event routing.
- `ui`: Activities/Fragments/ViewModels/adapters.
- `model`: DTOs matching backend payloads.

## Implementation Phases

### Phase 1: Project Foundation

Tasks:

1. Add `INTERNET` permission and cleartext dev setting.
2. Add dependencies.
3. Create `AppConfig`.
4. Create `Result<T>` and `UiState<T>` wrappers.
5. Create `ApiClient` with Retrofit and OkHttp.
6. Add basic `MainActivity` navigation container.

Acceptance criteria:

- App builds from Android Studio.
- App can execute a simple unauthenticated HTTP request or fail gracefully with a visible error.
- No network call uses raw `localhost`.

Algorithm: environment selection

```text
if BuildConfig.DEBUG:
    if running on emulator:
        use 10.0.2.2 URLs
    else:
        use configured LAN IP from AppConfig
else:
    use production/staging URLs
```

Do not auto-detect LAN IP at runtime. Keep URLs explicit so failures are understandable.

### Phase 2: Keycloak Authentication

Implement login with Keycloak password grant first because it is simplest for a native prototype. If backend/frontend later require Authorization Code + PKCE, replace the login implementation but keep `TokenManager` and repositories stable.

Keycloak token endpoint:

```text
POST {KEYCLOAK_URL}/realms/loci-realm/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

client_id=angular
grant_type=password
username=<username>
password=<password>
```

Expected stored values:

- `access_token`
- `refresh_token`
- `expires_in`
- `refresh_expires_in`
- computed `accessTokenExpiresAt`
- computed `refreshTokenExpiresAt`

Token storage:

- Use `EncryptedSharedPreferences` if available without excessive setup.
- Otherwise use `SharedPreferences` during the first implementation, then upgrade.

Algorithm: login

```text
function login(username, password):
    show loading
    response = AuthApi.getToken(username, password)
    if response success:
        TokenManager.save(response)
        preload current user profile
        connect socket
        navigate to main screen
    else if status is 400 or 401:
        show "Invalid username or password"
    else:
        show generic network/server error
```

Algorithm: attach token

```text
for every API request except Keycloak token endpoints:
    token = TokenManager.getAccessToken()
    if token exists:
        add header "Authorization: Bearer <token>"
```

Algorithm: refresh token

```text
function getValidAccessToken():
    if no access token:
        return unauthenticated

    if now < accessTokenExpiresAt - 60 seconds:
        return current access token

    if refresh token exists and now < refreshTokenExpiresAt - 60 seconds:
        call refresh endpoint synchronously inside authenticator lock
        if refresh succeeds:
            save new tokens
            return new access token
        else:
            clear session
            return unauthenticated

    clear session
    return unauthenticated
```

Use a lock in `TokenAuthenticator` so multiple simultaneous 401 responses trigger only one refresh request.

Acceptance criteria:

- User can login.
- Token is attached to protected API calls.
- App returns to login when refresh fails.

### Phase 3: Core REST APIs

Use existing backend and `loci-api` request collection as the source of truth.

Implement these APIs first:

User:

```text
GET    /users/me
PATCH  /users/me
PATCH  /users/me/avatar
GET    /users/me/settings
PATCH  /users/me/settings
GET    /users/{publicId}
GET    /users/search?q=<query>
GET    /users/suggests
```

Contact/social:

```text
GET    /friends
POST   /contact-requests/{userId}
DELETE /contact-requests/{userId}
GET    /contact-requests
POST   /contact-requests/user/{requestUserId}/accept
POST   /contact-requests/user/{requestUserId}/reject
POST   /blocks/{blockUserId}
DELETE /blocks/{blockUserId}
GET    /blocks
```

Conversation/message:

```text
GET    /conversations
GET    /conversations/{conversationId}
GET    /conversations/{conversationId}/messages
POST   /conversations
GET    /conversations/user/{userId}
POST   /messages/individual/send
POST   /messages/individual/ack-receive
```

Group:

```text
POST   /conversations/group
GET    /groups/{conversationId}
PATCH  /groups/{conversationId}
```

If an endpoint name differs from this list, inspect `loci-api/*.yml` and frontend services, then follow the existing backend contract.

Repository algorithm:

```text
function repositoryCall(apiCall):
    try:
        response = apiCall.execute()
        if response.isSuccessful and response.body != null:
            return Result.success(response.body)
        if response.code == 401:
            return Result.unauthorized()
        if response.code in 400..499:
            parse backend ProblemDetail if possible
            return Result.clientError(message)
        if response.code in 500..599:
            return Result.serverError(message)
    catch IOException:
        return Result.networkError()
    catch Exception:
        return Result.unknownError()
```

Acceptance criteria:

- Login screen can fetch `/users/me`.
- Chat list can fetch conversations.
- Contact screen can search users and send/accept/reject friend requests.

### Phase 4: Navigation and UI

Use one `MainActivity` and multiple fragments unless the current app structure changes.

Suggested screens:

1. `LoginFragment`
2. `MainFragment` or `MainActivity` with bottom navigation
3. `ChatListFragment`
4. `ChatFragment`
5. `ContactsFragment`
6. `FriendRequestsFragment`
7. `ProfileFragment`
8. `SettingsFragment`
9. `GroupCreateFragment`
10. `GroupProfileFragment`
11. `MediaViewerFragment`

Minimum viable UI:

- Bottom nav: Chats, Contacts, Notifications, Profile.
- Chat list shows avatar, name, last message, timestamp, unread count.
- Chat screen shows message bubbles, status indicator, input, send button, attachment button.
- Contacts screen supports search, suggestions, friend requests, block list.

Algorithm: screen state

```text
state = Loading | Content(data) | Empty | Error(message)

on screen open:
    render Loading
    call ViewModel.load()
    if success and data not empty:
        render Content
    else if success and empty:
        render Empty
    else:
        render Error with retry button
```

Acceptance criteria:

- User can navigate between primary sections.
- Loading, empty, error, and content states exist for each list screen.
- UI does not crash when optional fields like avatar URL are null.

### Phase 5: Chat Data Model and Message Algorithms

Create local mobile message states even if backend has its own states.

Mobile message states:

```text
LOCAL_PENDING
LOCAL_FAILED
SENT
DELIVERED
SEEN
```

Suggested message fields:

```text
localId: UUID generated by mobile
messageId: backend UUID, nullable until send succeeds
conversationId
senderId
content
type: text | image | video | file | call
attachments
createdAt
status
isMine
```

Algorithm: send text message with optimistic UI

```text
function sendText(conversationId, text):
    trimmed = text.trim()
    if trimmed is empty:
        return

    localMessage = Message(
        localId = random UUID,
        messageId = null,
        conversationId = conversationId,
        content = trimmed,
        status = LOCAL_PENDING,
        isMine = true,
        createdAt = now
    )

    insert localMessage into visible list
    scroll to bottom
    clear input

    call POST /messages/individual/send or group equivalent
    if success:
        replace local message by localId with server message
        status = SENT unless server returns later status
    else:
        mark localId as LOCAL_FAILED
        show retry affordance
```

Algorithm: retry failed message

```text
function retryMessage(localId):
    message = find local message
    if message.status != LOCAL_FAILED:
        return
    mark LOCAL_PENDING
    call send API with original content/attachment
    on success replace with server message
    on failure mark LOCAL_FAILED again
```

Algorithm: merge incoming message

```text
function mergeMessage(incoming):
    if messageId already exists in list:
        update existing message fields/status
        return

    if incoming has clientRequestId/localId and local pending exists:
        replace local pending with incoming
        return

    insert incoming sorted by createdAt

    if incoming.senderId != currentUserId:
        call ackReceiveMessage(incoming.messageId)
        if chat is currently open and visible:
            call markSeen(incoming.messageId or conversationId)
```

If backend does not support client-generated IDs, deduplicate by `messageId` and tolerate the optimistic local message being replaced only after REST response.

Algorithm: paginate messages

```text
function loadInitialMessages(conversationId):
    page = first page or latest cursor
    render newest messages at bottom

function loadOlderMessages():
    if alreadyLoading or noMorePages:
        return
    remember firstVisibleMessageId and offset
    fetch previous page
    prepend older messages
    restore scroll position using remembered item
```

Acceptance criteria:

- Sending message immediately appears in UI.
- Failed send is visible and retryable.
- Duplicate messages are not shown when REST response and WebSocket event both arrive.

### Phase 6: WebSocket/STOMP Realtime

Backend websocket endpoint candidates from the existing code:

```text
ws://10.0.2.2:8080/api/v1/ws
ws://10.0.2.2:8080/api/v1/ws/messages
ws://10.0.2.2:8080/api/v1/ws/notifications
ws://10.0.2.2:8080/api/v1/ws/presence
```

Start with:

```text
ws://10.0.2.2:8080/api/v1/ws
```

STOMP connect headers:

```text
Authorization: Bearer <access_token>
```

Subscribe to these destinations, matching frontend/backend constants:

```text
/user/queue/messages.receive
/user/queue/messages.sent
/user/queue/messages.delivered
/user/queue/messages.seen
/user/queue/notifications.new
/user/queue/notifications.update
/user/queue/calls.signal
/topic/messages.receive-{conversationId}
/topic/presence.user-{userId}.update
/topic/presence.group-{conversationId}.update
```

Algorithm: socket lifecycle

```text
on login success or app resume with valid token:
    if socket not connected:
        connect with Authorization header

on connected:
    subscribe global user queues:
        messages.receive
        messages.sent
        messages.delivered
        messages.seen
        notifications
        calls
    subscribe active chat group topic if currently open

on token refresh:
    reconnect socket with new access token

on logout:
    unsubscribe all
    disconnect socket
    clear token
```

Algorithm: reconnect with backoff

```text
attempt = 0
while user is authenticated and socket disconnected:
    delay = min(30 seconds, 2^attempt seconds)
    wait delay
    if token expired:
        refresh token
    try connect
    if success:
        attempt = 0
        resubscribe all active destinations
    else:
        attempt += 1
```

Algorithm: route socket events

```text
on socket message(destination, body):
    parse body to model based on destination
    if destination contains messages.receive:
        MessageRepository.mergeIncoming(body)
        update chat list last message
    if destination contains messages.sent/delivered/seen:
        MessageRepository.updateStatus(body.messageId, body.status)
    if destination contains notifications:
        NotificationRepository.merge(body)
    if destination contains presence:
        UserRepository.updatePresence(body)
    if destination contains calls.signal:
        CallRepository.handleSignal(body)
```

Acceptance criteria:

- Mobile receives new direct messages without manual refresh.
- Mobile receives group messages for open group conversation.
- Message delivered/seen status updates do not create duplicates.
- Socket reconnects after app background/foreground or network interruption.

### Phase 7: Media Upload and MinIO Display

Do not upload directly to MinIO from mobile unless backend explicitly supports direct signed URLs. Prefer backend-mediated upload so auth, validation, and metadata stay centralized.

Profile avatar:

```text
PATCH /users/me/avatar
Content-Type: multipart/form-data
field name: inspect frontend ProfileApi and backend resource before finalizing
```

Chat attachment:

Inspect frontend message input/direct/group APIs and backend resources to confirm whether media is sent in the message payload or uploaded first.

Algorithm: send media message

```text
function sendMedia(conversationId, uri):
    metadata = ContentResolver.query(uri)
    validate file size and mime type
    show local pending attachment preview

    if backend has upload endpoint:
        upload multipart file
        receive attachment metadata/url
        send message with attachment metadata
    else if message send endpoint accepts multipart:
        send multipart directly to message endpoint

    on success:
        replace local pending message with server message
    on failure:
        mark local message LOCAL_FAILED
```

Algorithm: load media URL

```text
function normalizeMediaUrl(url):
    if url starts with "http://localhost:9000":
        replace "localhost" with "10.0.2.2" for emulator builds
    if url is absolute:
        return url
    else:
        return MINIO_PUBLIC_URL + "/" + url without leading slash
```

Use Glide for image/avatar/video thumbnail loading. Show placeholder while loading and fallback on error.

Acceptance criteria:

- Profile avatar upload works.
- Image message displays after upload.
- URLs generated as `localhost` are normalized for emulator.

### Phase 8: Contacts, Profile, Notifications

Contacts algorithms:

```text
search:
    wait 300 ms after user stops typing
    cancel previous request
    if query blank:
        show suggestions
    else:
        call /users/search?q=query

send friend request:
    optimistically set row status to REQUEST_SENT
    call API
    on success keep returned status
    on failure restore previous status
```

Profile algorithms:

```text
load my profile:
    call /users/me
    cache in memory
    update current user id for chat isMine checks

update profile:
    disable save while request is running
    validate non-empty display fields
    PATCH /users/me
    update local profile on success
```

Notification algorithms:

```text
load notifications on screen open
merge socket notifications by notificationId
sort newest first
badge count = unread notifications
```

Acceptance criteria:

- Friend request lifecycle works from mobile.
- Profile data can be viewed and updated.
- New notification socket events update badge/list.

### Phase 9: Group Chat

Implement only after direct chat is stable.

Tasks:

1. Create group conversation.
2. Display group profile and members.
3. Load group messages.
4. Subscribe to `/topic/messages.receive-{conversationId}` when opening the group chat.
5. Ack received group messages.
6. Update group presence using `/topic/presence.group-{conversationId}.update`.

Algorithm: group subscription ownership

```text
on open group chat:
    unsubscribe previous active group topic if different
    subscribe /topic/messages.receive-{conversationId}
    subscribe /topic/presence.group-{conversationId}.update

on leave group chat:
    unsubscribe group-specific topics unless another visible screen needs them
```

Acceptance criteria:

- Group message appears realtime while group chat is open.
- Group messages are not duplicated after refresh.

### Phase 10: Video Call Integration

Implement after chat and socket are stable.

Existing backend/frontend indicate:

```text
LIVEKIT_URL=ws://10.0.2.2:7880
/user/queue/calls.signal
```

Tasks:

1. Inspect frontend `call.service.ts` and `call-signaling.service.ts`.
2. Mirror signaling payloads exactly.
3. Add LiveKit Android SDK if compatible.
4. Implement incoming call modal.
5. Implement call screen with join/leave/mute/camera controls.

Algorithm: call signaling

```text
caller taps call:
    request LiveKit token for conversation
    send call offer via backend signaling
    show ringing UI

callee receives offer:
    show incoming call modal
    if accept:
        request LiveKit token
        send answer
        join room
    if reject:
        send reject

either side ends:
    send end signal
    leave LiveKit room
    release camera/mic
```

Acceptance criteria:

- Incoming call notification appears.
- Accept/reject/end flows do not leave socket or media resources hanging.

## Error Handling Rules

Normalize all user-facing errors:

```text
401: session expired, navigate to login
403: no permission
404: item no longer exists
409: conflict, refresh current screen
413: file too large
500+: server unavailable
IOException: network unavailable
JSON parse error: incompatible client/server model
```

Never let raw stack traces appear in UI.

## Testing Checklist

Manual local test order:

1. Start `local-dev/docker compose`.
2. Start backend.
3. Open MinIO console and ensure bucket `loci-bucket-01` exists.
4. Run Android app in emulator.
5. Login with a known Keycloak user.
6. Fetch `/users/me`.
7. Search users.
8. Send/accept friend request.
9. Create/open direct conversation.
10. Send text message.
11. Open same user in web frontend or second emulator and verify realtime delivery.
12. Send image/file message.
13. View uploaded media in MinIO console.
14. Background and foreground app, verify socket reconnect.
15. Logout and verify token/socket cleanup.

Automated tests to add:

- Unit test `TokenManager` expiry logic.
- Unit test `TokenAuthenticator` single refresh behavior.
- Unit test message merge/deduplication.
- Unit test media URL normalization.
- Repository tests with mocked Retrofit responses.

## Completion Definition

The mobile app is considered complete for the first stable version when:

- It logs in through Keycloak.
- It loads current user profile.
- It shows chat list.
- It opens direct chat.
- It sends and receives direct messages.
- It updates message sent/delivered/seen state.
- It supports contacts and friend requests.
- It uploads and displays at least image attachments.
- It survives token refresh, app background/foreground, and socket reconnect.
- It has clear loading/empty/error states on main screens.

## Kiro Execution Notes

Work in small commits or implementation chunks:

1. Foundation and build config.
2. Auth.
3. REST models/APIs/repositories.
4. Main navigation/UI.
5. Direct chat REST.
6. WebSocket realtime.
7. Media upload/display.
8. Contacts/profile/notifications polish.
9. Group chat.
10. Video call.

Before changing an endpoint or payload shape, inspect these sources:

- `loci-api/**/*.yml`
- `loci-frontend/src/app/features/**/services/*.ts`
- `loci-frontend/src/app/features/chat/**/*.ts`
- `loci-backend/src/main/java/com/loci/loci_backend/**/resource/*.java`
- `loci-backend/src/main/java/com/loci/loci_backend/common/websocket/infrastructure/WsPaths.java`

Prefer matching the existing backend/frontend contracts exactly over inventing mobile-only contracts.

## Pending / Future Mobile Features (Chưa thực hiện)

Dưới đây là các tính năng quan trọng hiện tại chưa được triển khai trên ứng dụng Mobile, cần được ưu tiên trong các sprint tiếp theo:

1. **Tích hợp Video/Voice Call (LiveKit)**: Tích hợp `LiveKit Android SDK` để xử lý gọi thoại và gọi video theo thời gian thực tương tự bản Web/Desktop.
2. **Gửi và Hiển thị Media (Hình ảnh/File)**: Xây dựng luồng upload file/ảnh lên MinIO và hiển thị trực tiếp trong Message Bubble.
3. **Hoàn thiện tính năng Tạo Nhóm (Create Group)**: Gắn API xử lý chọn nhiều bạn bè và tạo group chat mới.
4. **Thông báo đẩy (Push Notifications với Firebase FCM)**: Nhận thông báo tin nhắn và cuộc gọi khi ứng dụng chạy ngầm (background) bằng Firebase Cloud Messaging.
5. **Kết nối Realtime (WebSocket/STOMP)**: Tích hợp kết nối WebSocket để đồng bộ tin nhắn theo thời gian thực mà không cần reload (pull-to-refresh).
