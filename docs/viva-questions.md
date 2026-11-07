# NexaChat Viva Question Bank

> Bộ câu hỏi bảo vệ bằng tiếng Việt và tiếng Anh. Câu trả lời nêu lý do thiết
> kế và đường dẫn mã nguồn để người trình bày có thể kiểm tra tại revision
> đang bảo vệ. Xem thêm [`docs/rtm.md`](rtm.md) để đối chiếu rubric.

## 1. Kiến trúc Android

### Q1.1 — Android client được viết bằng ngôn ngữ nào?

*Which language is used for the Android client?*

**Trả lời.** Client Android và server đều dùng Java 17. Cây Android hiện có
385 tệp Java trong source tree, bao gồm mã ứng dụng và kiểm thử. Có thể kiểm tra
cấu hình tương thích Java trong `android/app/build.gradle` và các lớp dưới
`android/app/src/main/java/`. Toàn bộ Gradle build scripts (`settings.gradle`, `build.gradle`, `app/build.gradle`) cũng dùng Groovy DSL chuẩn.

**Câu hỏi gài.** *“Vậy UI cũng là Java?”* — Đúng. Activity, Fragment, ViewModel,
adapter và logic ứng dụng đều là lớp Java; layout được khai báo bằng Android
XML.

### Q1.2 — Activity chính và Navigation hoạt động thế nào?

*How do the launcher activity and navigation work?*

**Trả lời.** `MainActivity.java` là launcher activity và kế thừa
`core/ui/NexaHostActivity.java`. Host đặt `NavHostFragment`; các destination
được khai báo trong `res/navigation/nav_main.xml`. Mỗi màn nghiệp vụ là một
Java Fragment. `CallActivity.java` là Activity riêng cho giao diện cuộc gọi.

### Q1.3 — Vì sao dùng XML Views và Fragments?

*Why use XML Views and Fragments?*

**Trả lời.** Cấu trúc này dùng component Android native và giữ riêng layout,
navigation, state màn hình, cùng logic nghiệp vụ. Material 3 cung cấp các
component giao diện nhất quán. ViewBinding liên kết view trong XML với lớp
Java được sinh khi build, giảm việc tìm view thủ công và lỗi ép kiểu.

### Q1.4 — ViewModel giúp gì khi xoay màn hình?

*How does a ViewModel help during a configuration change?*

**Trả lời.** Android giữ ViewModel qua thay đổi cấu hình, nên Fragment mới có
thể quan sát lại cùng state mà không tạo lại dữ liệu chỉ vì Activity được dựng
lại. `SavedStateHandle` giữ những giá trị nhỏ cần khôi phục sau khi process bị
tạo lại. View và adapter vẫn được dựng lại theo lifecycle bình thường.

### Q1.5 — Trạng thái hiển thị và sự kiện một lần được quản lý ra sao?

*How are screen state and one-time events represented?*

**Trả lời.** ViewModel công bố `LiveData` hoặc `MutableLiveData` cho trạng thái
màn. Các thao tác chỉ nên xử lý một lần dùng event wrapper có cờ đã tiêu thụ;
trong mã hiện tại có `ChatSingleLiveEvent` và `GroupSingleLiveEvent`.
`SavedStateHandle` giữ state có thể khôi phục, còn sự kiện điều hướng không
được coi là dữ liệu bền.

### Q1.6 — RxJava 3 dùng ở những trường hợp nào?

*Where is RxJava 3 used?*

**Trả lời.** `Single<T>`, `Maybe<T>` và `Completable` biểu diễn tác vụ bất
đồng bộ một lần. `Flowable<T>` đưa dữ liệu hoặc sự kiện dạng stream tới tầng
repository và ViewModel. `BehaviorProcessor<T>` giữ giá trị mới nhất cho
subscriber tiếp theo; `PublishProcessor<T>` phát sự kiện cho subscriber hiện
tại. Các lớp này thuộc namespace `io.reactivex.rxjava3`.

### Q1.7 — Công việc mạng chạy ở đâu và subscription được dọn thế nào?

*Where does network work run, and how are subscriptions disposed?*

**Trả lời.** Repository đăng ký thao tác mạng trên `Schedulers.io()`.
RxAndroid chuyển cập nhật cần thiết về main thread trước khi ghi state giao
diện. ViewModel gom subscription thuộc vòng đời của nó trong
`CompositeDisposable` và gọi `clear()` từ `onCleared()` để không giữ tham
chiếu sau khi màn bị loại bỏ.

### Q1.8 — Tại sao danh sách dùng ListAdapter và DiffUtil?

*Why use ListAdapter and DiffUtil for lists?*

**Trả lời.** `ListAdapter` nhận danh sách mới và dùng `DiffUtil.ItemCallback`
để xác định dòng nào đổi. RecyclerView chỉ cập nhật phần bị ảnh hưởng thay vì
vẽ lại toàn bộ danh sách. Có thể xem `feature/chat/adapter/MessageListAdapter.java`
và `feature/inbox/adapter/ConversationListAdapter.java`.

### Q1.9 — Vì sao Room là nguồn dữ liệu hiển thị chính?

*Why is Room the primary source for rendered message data?*

**Trả lời.** Truy vấn Room phát trạng thái mà màn hình quan sát. REST response
và STOMP event được ghi hoặc hợp nhất vào cơ sở dữ liệu cục bộ; UI đọc lại
danh sách theo thứ tự từ DAO. Nhờ vậy lịch sử tải lên và event realtime đi qua
cùng một điểm hợp nhất, đồng thời dữ liệu đã lưu có thể hiện lại sau khi mở
ứng dụng.

### Q1.10 — Room v7 được nâng cấp dữ liệu thế nào?

*How is the Room v7 database upgraded?*

**Trả lời.** `AppDatabase.java` khai báo schema version 7 và các migration
tường minh. DAO gồm `MessageDao`, `ConversationDao`, `OutboxDao`, `ReactionDao`
và `PinDao`. `RoomMigrationTest.java` dùng `MigrationTestHelper` để kiểm tra
chuyển đổi schema. Migration cần giữ dữ liệu hợp lệ, không chỉ làm cho build
biên dịch.

## 2. Mạng và lưu trữ

### Q2.1 — Retrofit call được bọc thế nào?

*How are Retrofit calls wrapped?*

**Trả lời.** `core/network/ApiResult.java` có `safeApiCall(Call<T>)`, trả về
`Single<ApiResult<T>>`. Hàm phân loại response thành công, lỗi HTTP hoặc lỗi
kết nối và thực thi Retrofit call trên I/O scheduler. Repository ánh xạ kết
quả transport thành kiểu kết quả của miền ứng dụng.

### Q2.2 — Token Android được lưu ở đâu?

*How are Android tokens stored?*

**Trả lời.** `TokenStore.java` đọc ghi Preferences qua
`RxDataStore<Preferences>`. `AesGcmTokenCipher.java` dùng AES-256-GCM với khóa
được giữ bởi Android Keystore. Keystore giảm việc để khóa mã hóa dưới dạng
thô trong vùng lưu ứng dụng; server vẫn phải xác thực token và kiểm tra quyền
ở mọi endpoint.

### Q2.3 — Room và DataStore khác vai trò gì?

*What is the difference between Room and DataStore here?*

**Trả lời.** Room lưu dữ liệu có cấu trúc như hội thoại, tin nhắn, outbox,
reaction và pin. DataStore lưu Preferences nhỏ, trong trường hợp này gồm token
được mã hóa. Chọn nơi lưu theo hình dạng và cách truy vấn dữ liệu, không dùng
Preferences để thay cơ sở dữ liệu quan hệ.

### Q2.4 — STOMP chạy trên transport nào?

*Which transport carries STOMP frames?*

**Trả lời.** Android mở WebSocket bằng OkHttp và gửi STOMP frame qua kết nối
đó. Client có codec STOMP riêng trong `core/network/stomp/`; server đăng ký
endpoint `/ws`. STOMP cung cấp destination và loại lệnh cần cho subscribe,
send và event, còn WebSocket giữ kết nối hai chiều.

### Q2.5 — Vì sao server chỉ broadcast sau khi ghi thành công?

*Why does the server broadcast only after a successful commit?*

**Trả lời.** Service ghi message và dữ liệu liên quan trong giao dịch. Listener
ở pha `AFTER_COMMIT` chỉ gửi event khi database xác nhận commit. Nếu transaction
rollback, client không nhận một tin nhắn giả mà API lịch sử không thể trả lại.
Nếu event bị lỡ sau commit, client có thể resync qua REST.

### Q2.6 — Gửi lặp có tạo hai tin nhắn không?

*Can a retried send create two messages?*

**Trả lời.** Client gắn một định danh yêu cầu vào lần gửi và dùng lại nó khi
retry. Server dùng khóa idempotency để nhận ra yêu cầu trùng; database cùng
service là điểm quyết định cuối. Đây là cách xử lý timeout mà client không
biết request trước đã tới server hay chưa.

### Q2.7 — Gửi offline hoạt động ra sao?

*How does offline sending work?*

**Trả lời.** Tin được đưa vào outbox Room với trạng thái đang chờ. Khi mạng có
lại, repository thử gửi và cập nhật trạng thái theo kết quả. UI có thể hiển
thị tin chờ hoặc lỗi, nhưng trạng thái local không có nghĩa server đã lưu tin.
Định danh gửi được giữ trong lần retry để tránh tạo bản sao.

## 3. Bảo mật và tính năng

### Q3.1 — Ai quyết định một người có thể đọc hoặc gửi trong hội thoại?

*Who decides whether a user may read or send in a conversation?*

**Trả lời.** Server là nơi cưỡng chế quyền. REST controller/service kiểm tra
người dùng đã xác thực và quan hệ thành viên; STOMP channel interceptor kiểm
tra frame subscribe/send, còn service xác nhận quy tắc nghiệp vụ. Ẩn nút phía
Android chỉ cải thiện trải nghiệm, không phải một hàng rào bảo mật.

### Q3.2 — Quyền truy cập tệp được bảo vệ thế nào?

*How is attachment access protected?*

**Trả lời.** Server giới hạn loại và kích thước upload, kiểm tra nội dung tệp,
và xác thực quyền trước khi trả tệp. Client gọi endpoint có token thay vì coi
đường dẫn tệp là bí mật. Kiểm tra quyền ở server cần hoạt động cả khi request
được gửi ngoài giao diện Android.

### Q3.3 — Hilt được dùng để làm gì?

*What does Hilt provide?*

**Trả lời.** Hilt cung cấp dependency graph cho Application, Activity,
ViewModel và các module cung cấp repository hoặc API. Dự án dùng plugin
`dagger.hilt.android` và Java `annotationProcessor` để sinh mã cần thiết.
Injection giúp kiểm thử thay dependency thật bằng mock hoặc fake.

### Q3.4 — Glide và Media3 đảm nhiệm phần nào?

*What do Glide and Media3 handle?*

**Trả lời.** Glide nạp và hiển thị ảnh. Media3 ExoPlayer xử lý phát video và
âm thanh. Các lớp media này không quyết định ai được phép tải nội dung; việc
kiểm tra quyền vẫn thuộc server và lớp repository.

### Q3.5 — Tính năng cuộc gọi được chia trách nhiệm thế nào?

*How are call responsibilities split?*

**Trả lời.** `CallActivity.java` cung cấp giao diện cuộc gọi trên Android.
Server trao đổi signaling và quản lý trạng thái cuộc gọi; client dùng WebRTC
cho media. Vì signaling không truyền toàn bộ media, chất lượng cuộc gọi còn
phụ thuộc mạng, quyền microphone và cấu hình ICE.

### Q3.6 — Tóm tắt hội thoại gửi dữ liệu ra ngoài khi nào?

*When does conversation summarization send data externally?*

**Trả lời.** Tóm tắt có thể chạy bằng engine trích xuất ở server. Dịch vụ mô
hình bên ngoài chỉ được gọi theo lựa chọn đồng ý riêng của người dùng. Kết
quả có citation để người đọc quay lại tin nguồn; citation hỗ trợ kiểm chứng,
không đảm bảo mọi câu diễn giải đều đúng.

### Q3.7 — Client có mã hóa đầu cuối không?

*Does the client provide end-to-end encryption?*

**Trả lời.** Không. TLS bảo vệ dữ liệu trên đường truyền trong môi trường đã
cấu hình HTTPS/WSS; token cục bộ được mã hóa bằng Android Keystore. Server vẫn
có thể xử lý nội dung tin nhắn, vì vậy đây không phải mã hóa đầu cuối. Muốn
bổ sung cần thiết kế trao đổi khóa, đa thiết bị, khôi phục và xử lý thành viên
nhóm.

### Q3.8 — Cài đặt ngôn ngữ và chủ đề nằm ở đâu?

*Where are locale and theme preferences handled?*

**Trả lời.** Giao diện dùng Android string resources cho tiếng Việt và tiếng
Anh. `LanguageSettingsFragment.java` cập nhật lựa chọn ngôn ngữ; cài đặt chủ
đề được cung cấp qua ViewModel và repository. Tài liệu audit trong `docs/`
ghi số lượng chuỗi và phạm vi bản địa hóa tại revision tương ứng.

### Q3.9 — Layout ngang được chọn thế nào?

*How is the landscape chat layout selected?*

**Trả lời.** Android tự chọn layout theo resource qualifier. Chat dọc dùng
`res/layout/fragment_chat.xml`; chat ngang dùng
`res/layout-land/fragment_chat.xml`. ViewModel và `SavedStateHandle` giữ dữ liệu
màn hình cần thiết khi cấu hình đổi; view được tạo lại với layout phù hợp.

## 4. Kiểm thử và bảo vệ

### Q4.1 — Kiểm thử Android bao gồm những công cụ nào?

*Which tools are used for Android unit testing?*

**Trả lời.** Unit tests dùng JUnit 4, Mockito, RxJava `TestObserver`/
`TestSubscriber`, `TestScheduler` và `InstantTaskExecutorRule`. Cây test hiện
có 542 case dưới `android/app/src/test/java`. Có thể chạy bằng
`cd android && ./gradlew testDebugUnitTest`.

### Q4.2 — 542 test có chứng minh ứng dụng chạy tốt trên mọi máy không?

*Do 542 unit tests prove the app works on every device?*

**Trả lời.** Không. Unit tests xác nhận logic ở phạm vi chúng kiểm tra. Chúng
không thay thế kiểm thử thiết bị, quyền hệ điều hành, mạng yếu, giao diện thực,
hoặc dịch vụ server đang chạy. Khi báo cáo kết quả, cần nêu lệnh, revision,
môi trường và kết quả thật.

### Q4.3 — Vì sao có MigrationTestHelper?

*Why use MigrationTestHelper?*

**Trả lời.** Migration thay đổi dữ liệu người dùng đã lưu nên cần kiểm tra
chuỗi schema cũ sang schema mới. `RoomMigrationTest.java` dùng
`MigrationTestHelper` để tạo schema lịch sử và xác nhận migration. Kiểm tra
đúng schema hiện tại không tự chứng minh mọi đường nâng cấp đã thành công nếu
test không đi qua đường đó.

### Q4.4 — Server test có thể chạy độc lập với Android không?

*Can the server tests run independently of Android?*

**Trả lời.** Có. Server build bằng Maven và có test riêng; Android build bằng
Gradle. Integration test phía server cần PostgreSQL theo cấu hình test. Tách
build giúp khoanh vùng lỗi, nhưng thay đổi contract REST/STOMP vẫn cần xác minh
cả hai phía.

### Q4.5 — Word report được đồng bộ bằng cách nào?

*How is the Word report kept in sync?*

**Trả lời.** `docs/report.md` là nguồn nội dung. Chạy
`python tools/build-report.py` để sinh `docs/report.docx`; script mở lại tệp
được sinh và đối chiếu số heading, bảng, cùng các dấu Markdown còn sót. Khi
nội dung thay đổi, cần chạy lại script và kiểm tra output cùng trạng thái Git.

### Q4.6 — Vì sao report nhấn mạnh đường dẫn mã nguồn?

*Why does the report cite source paths?*

**Trả lời.** Đường dẫn giúp hội đồng chuyển từ claim sang implementation có
thể kiểm tra. Đường dẫn phải khớp tên và vị trí class trong commit đang bảo vệ;
placeholder, log cũ hoặc một test chưa chạy không được trình bày như bằng
chứng của kết quả hiện tại.

## 5. Nhắc nhanh trước phần bảo vệ

- Nêu ranh giới giữa local state trong Room và dữ liệu đã commit trên server.
- Phân biệt REST request/response với STOMP event realtime.
- Giải thích vì sao retry cần định danh idempotency.
- Chỉ ra `MainActivity`, `NexaHostActivity`, `nav_main.xml` và một Fragment
  cụ thể khi được hỏi về luồng UI.
- Khi trả lời về test, phân biệt unit test, migration test, integration test
  và kiểm thử trên thiết bị.
- Nêu rõ giới hạn đã biết; không biến hướng phát triển thành tính năng đã giao.
