# 스트리밍 PMTiles 라이터: 설계 메모 (v0.1.0, 리뷰 반영)

목표: Tileverse 등 기존 Java 라이브러리와 달리 타일 전체를 메모리에 올리지 않고, MBTiles 중간 변환 없이 PMTiles v3를 기록한다.

## 핵심 결정
- **Java 17, 런타임 의존성 없음.** 내부 압축은 NONE/GZIP 내장, zstd·brotli는 `Codec` 인터페이스로 사용자가 연결.
- **출력: 타일은 최종 위치에 한 번만 쓴다.** 앞 16 KiB를 비워 두고 타일을 16384번 바이트부터 기록, finish 때 leaf와 메타데이터를 뒤에 붙이고 앞 16 KiB(header + root)를 마지막에 기록. 스펙상 header 위치와 "root는 첫 16 KiB 안"만 고정이므로 유효한 배치다. 내부 `Sink`가 "본문 순차 쓰기 + 마지막에 앞 16 KiB" 두 동작만 제공한다.
  - `open(Path)` → `FileSink`: RandomAccessFile, 마지막에 0번으로 seek.
  - `open(MultipartUpload, partSize)` → `MultipartSink`: 파트 2..n을 차는 대로 업로드, 파트 1(앞 16 KiB + 첫 타일)을 마지막에 업로드. S3는 파트 번호로 조립하므로 순서 무관. 모든 `MultipartUpload` 호출(파트, complete, abort)은 전용 업로드 스레드 하나에서 실행한다: 네트워크가 writer 락을 잡지 않고, interrupt된 생산자 스레드가 SDK에 닿지 않는다. 버퍼 대기는 `awaitUninterruptibly`. 힙은 파트 3개(파트 1 + 교대 버퍼 2개). partSize는 5 MiB–1 GiB를 열 때 검사. 최대 10,000 파트. `open()` 실패 시에도 `abort()`를 부른다. 결과는 파일 출력과 바이트 단위로 같다.
  - 메타데이터는 작아도 앞 16 KiB에 넣지 않고 항상 맨 뒤에 둔다. go-pmtiles `verify`는 위치가 아니라 파일 길이만 보고, `127+root+meta+leaf+tile`(빈틈 없음) 또는 `16384+meta+leaf+tile`(패딩)만 허용한다. 앞에 넣으면 메타데이터 길이만큼 짧아져 실패한다. 앞에 넣어도 얻는 것이 없다: pmtiles.js `getMetadata()`는 첫 16 KiB를 재사용하지 않고 항상 별도 range 요청을 보낸다. Planetiler(`WriteablePmtiles`)도 같은 패딩 배치를 쓴다.
  - 비정렬 입력은 clustered가 아니다. 타일 데이터를 옮기지 않기 때문.
- **엔트리는 24 B 레코드로 디스크 스필** + 청크 정렬 + k-way 병합(fan-in 128, 초과 시 다단계). 입력이 정렬돼 있는 동안은 같은 run 파일에 이어 붙여서, 정렬 입력은 run 1개로 끝나고 병합이 필요 없다.
- **leaf 크기 규칙은 go-pmtiles와 동일**: 엔트리 16384개 미만이고 들어가면 root만 사용. 아니면 `max(4096, n/3500)`에서 시작해 root가 16,257 B 이하가 될 때까지 ×1.2. leaf는 1단계만.
- **중복 제거**: SHA-256 앞 128비트 키, LRU(기본 10만 개). 연속 tile ID에 같은 내용이면 RLE.
- **쓰기 I/O는 java.io**(RandomAccessFile, FileInput/OutputStream). NIO 채널은 스레드 interrupt 시 닫혀서, 취소된 생산자 하나가 아카이브 전체를 망가뜨렸기 때문. multipart는 업로드를 라이브러리 스레드에서 하므로 생산자·finish 스레드의 interrupt가 SDK에 전달되지 않는다.
- **수명주기**: `finish()` 명시 호출. 성공 전에 `close()`되면 파일은 삭제하고 multipart는 `abort()`를 호출한다. finish 성공 후 임시 파일 정리가 실패해도 finish는 성공으로 반환하고, close()가 정리를 다시 시도하면서 오류를 보고한다.
- 공개 API 최소화: `open(Path)`, `open(MultipartUpload, partSize)`, `write(z,x,y,bytes)`, `writeTileId(id,bytes)`, `setMetadata`, `finish`, `close`. Builder 옵션은 tileType, tileCompression, internalCompression, metadata, bounds, center, tempDirectory, dedupCacheEntries, sortChunkEntries.
- 패키지 `pmtiles.stream`, groupId `pmtiles.stream`: 임시 이름이라 바꿔도 됨.

## 알려진 메모리 예외
- leaf 디렉터리 1개 크기 = `n/3500` 엔트리 × 24 B → 엔트리 10억 개당 약 7 MiB.

## 리뷰 이력
**2026-11-02 코드 리뷰 + 단순화**
- 별도 에이전트가 독립 리뷰하고, 무작위 설정 3,700개를 퍼징했다. 스펙 위반이나 출력 오류는 없었다.
- 리뷰 지적 6건을 반영했다.
  - 정렬된 스트림 입력에서도 반복 오프셋 맵이 커지던 문제: 디스크 로그로 바꿈.
  - 생산자 스레드 interrupt 시 아카이브가 망가지던 문제: java.io로 전환.
  - 생성자 실패 시 파일이 새던 문제: 출력 파일을 마지막에 열도록 순서 변경.
  - 스트림 모드 실패 계약 문서화.
  - finish 후 정리 실패를 오류로 보고하던 동작 수정.
  - leaf 메모리 문서화.
- 단순화
  - 제거: LongLongMap, 테스트 전용 디코더, ConcatCursor·MemorySource, `clusterOnFinish`·`writeBufferBytes` 옵션, off/len 오버로드와 TileCoord 오버로드, `TileId.zoomOf`.
  - extent는 최대 줌만 추적하도록 줄임.
  - 결과: main 코드 2,081줄에서 1,680줄로 줄었고, 6개 모드·입력 조합의 출력이 이전 버전과 바이트 단위로 같다.

**2026-11-02 go-pmtiles 교차 검증 후 수정**
- `pmtiles verify`(v1.31.2)에서 파일 모드 출력이 길이 검사로 실패했다(메타데이터를 앞 16 KiB에 넣었기 때문). 스트림 모드는 통과.
- 파일 모드 메타데이터를 항상 맨 뒤에 두도록 변경. 길이만 맞춘 사본으로 나머지 검사(디렉터리, 오프셋, clustered 순서)는 clustered·비clustered 모두 통과함을 먼저 확인했다.

**2026-11-02 스트림 모드 제거, multipart 출력 추가**
- 기존 `open(OutputStream)`은 이름과 달리 타일 데이터 전체를 임시 파일에 모았다가 finish에서 복사했다(디스크 2배, I/O 2배). 앞으로 돌아갈 수 없는 순수 스트림에서는 header가 맨 앞이라 구조상 피할 수 없다.
- S3 multipart는 파트를 순서와 무관하게 올릴 수 있으므로 파일 모드와 같은 "앞 16 KiB는 마지막에" 방식이 가능하다. 그래서 출력을 `Sink`로 추상화하고 `open(OutputStream)`, 재배치(copy plan, 반복 오프셋 로그), `copyData`를 삭제했다. 비정렬 입력을 clustered로 바꾸는 기능도 함께 사라졌다.
- 파일 출력은 리팩터링 전과 바이트 단위로 같다(z0–10 Hilbert·row-major). multipart 출력도 같은 바이트다.

**2026-11-02 multipart 코드 리뷰 반영** (`/code-review`)
- 열기 실패 시 업로드가 abort되지 않아 S3에 미완성 업로드가 남던 문제 수정.
- 파트 업로드가 생산자 스레드에서, writer 락을 잡은 채 실행되던 문제: 전용 업로드 스레드로 이동. interrupt된 생산자가 SDK 호출을 실패시키지 않고, 업로드 동안 다른 생산자가 멈추지 않는다. 힙은 파트 2개에서 3개로 늘었다.
- partSize를 5 MiB–1 GiB로 검사(테스트만 package-private `openMultipart`로 우회).
- writer의 1 MiB 쓰기 버퍼를 `FileSink`로 옮겨 multipart에서 이중 복사 제거.
- 반영하지 않음: 10,000 파트 한도가 끝에서야 드러나는 문제(파트 크기 자동 증가가 대안), 비정렬 입력의 clustered 상실(의도한 변경).

## 검증 상태 (2026-11-02)
- `mvn test`(JDK 21, JUnit 5) 29개 통과(5회 반복).
- 스펙 문서만 보고 따로 작성한 Python 검사기(`tools/pmtiles_check.py`)로 파일·multipart × Hilbert·row-major z0–10 출력을 확인했다. z0–12 전체(2,237만 타일)는 multipart(5 MiB 파트) 두 조합을 `-Xmx64m`에서 기록했다. 8 MiB 파트 3개로는 64 MiB에서 OOM이 난다(파일 모드는 이전 버전에서 `-Xmx48m`). 기본 설정 권장 힙은 64 MiB 이상이다.
- go-pmtiles `pmtiles verify` v1.31.2: 파일·multipart z0–10, multipart z0–12 출력 통과. 공식 JS 리더와 실제 S3로는 아직 확인하지 않았다.

## 남은 일 후보
- 비정렬 입력을 clustered로 만드는 옵션(완료 시 재작성)
- MVT `vector_layers` 검사 옵션
- 실제 S3 버킷으로 multipart 업로드 확인
