# 스트리밍 PMTiles 라이터: 설계 메모 (v0.1.0, 리뷰 반영)

목표: Tileverse 등 기존 Java 라이브러리와 달리 타일 전체를 메모리에 올리지 않고, MBTiles 중간 변환 없이 PMTiles v3를 기록한다.

## 핵심 결정
- **Java 17, 런타임 의존성 없음.** 내부 압축은 NONE/GZIP 내장, zstd·brotli는 `Codec` 인터페이스로 사용자가 연결.
- **두 가지 출력 모드**
  - `open(Path)`: 타일을 출력 파일의 16384번 바이트부터 바로 기록. finish 때 leaf와 메타데이터를 뒤에 붙이고 header와 root를 앞 16 KiB에 기록. 스펙상 header 위치와 "root는 첫 16 KiB 안"만 고정이므로 유효한 배치다.
  - `open(OutputStream)`: 타일을 임시 파일에 모았다가 표준 순서로 스트리밍. 비정렬 입력이면 복사하면서 tile ID 순서로 재배치해 항상 clustered(옵션 없이 항상 수행).
- **엔트리는 24 B 레코드로 디스크 스필** + 청크 정렬 + k-way 병합(fan-in 128, 초과 시 다단계). 입력이 정렬돼 있는 동안은 같은 run 파일에 이어 붙여서, 정렬 입력은 run 1개로 끝나고 병합이 필요 없다.
- **leaf 크기 규칙은 go-pmtiles와 동일**: 엔트리 16384개 미만이고 들어가면 root만 사용. 아니면 `max(4096, n/3500)`에서 시작해 root가 16,257 B 이하가 될 때까지 ×1.2. leaf는 1단계만.
- **중복 제거**: SHA-256 앞 128비트 키, LRU(기본 10만 개). 연속 tile ID에 같은 내용이면 RLE.
- **스트림 모드 재배치용 "반복 오프셋"**: 내용별 첫 dedup 히트 때만 디스크 로그에 기록하고, 비정렬 입력으로 재배치가 필요할 때만 finish에서 맵으로 읽는다.
- **쓰기 I/O는 java.io**(RandomAccessFile, FileInput/OutputStream). NIO 채널은 스레드 interrupt 시 닫혀서, 취소된 생산자 하나가 아카이브 전체를 망가뜨렸기 때문. 예외로 finish의 스테이징 데이터 복사만 positional 채널 읽기를 쓴다(seek+read 대비 재배치가 약 10% 빠름). 그래서 finish 실행 스레드를 interrupt하면 finish가 중단될 수 있다.
- **수명주기**: `finish()` 명시 호출. 파일 모드에서 성공 전에 `close()`되면 출력을 삭제한다. 스트림 모드는 finish 도중 실패하면 이미 보낸 바이트를 되돌릴 수 없으므로 호출자가 업로드를 abort해야 한다. finish 성공 후 임시 파일 정리가 실패해도 finish는 성공으로 반환하고, close()가 정리를 다시 시도하면서 오류를 보고한다.
- 공개 API 최소화: `write(z,x,y,bytes)`, `writeTileId(id,bytes)`, `setMetadata`, `finish`, `close`. Builder 옵션은 tileType, tileCompression, internalCompression, metadata, bounds, center, tempDirectory, dedupCacheEntries, sortChunkEntries.
- 패키지 `pmtiles.stream`, groupId `pmtiles.stream`: 임시 이름이라 바꿔도 됨.

## 알려진 메모리 예외
- leaf 디렉터리 1개 크기 = `n/3500` 엔트리 × 24 B → 엔트리 10억 개당 약 7 MiB.
- 스트림 모드 + 비정렬 입력: finish에서 "반복된 서로 다른 내용 수 × 약 100 B" 맵을 만든다.

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

## 검증 상태 (2026-11-02)
- 테스트 27개 통과. 단, 작업 환경에서 Maven Central이 막혀 있어 javac와 JUnit API 스텁 러너로 실행했고 `mvn test`는 아직 돌리지 않았다.
- 스펙 문서만 보고 따로 작성한 Python 검사기(`tools/pmtiles_check.py`)로 모든 모드의 출력을 확인했다. z0–12 전체(2,237만 타일)를 3개 조합 모두 `-Xmx48m`에서 기록했다. 기본 설정 권장 힙은 64 MiB 이상이다.
- **아직 못 한 것**: go-pmtiles `pmtiles verify`와 공식 리더로 교차 검증(작업 환경에서 다운로드 불가).

## 남은 일 후보
- 파일 모드에서 비정렬 입력을 clustered로 만드는 옵션(완료 시 재작성)
- MVT `vector_layers` 검사 옵션
- S3 multipart OutputStream 예제
