# pmtiles-stream-writer

스트리밍 방식으로 PMTiles v3 아카이브를 쓰는 Java 라이브러리. 타일 수와 관계없이 힙 사용량이 일정해야 하고, MBTiles 중간 변환을 쓰지 않는다는 것이 존재 이유다. 설계 결정, 리뷰 이력, 검증 상태는 @docs/DESIGN.md 에 있다. 사용자용 설명은 README.md(한국어)에 있다.

## 명령
- 빌드/테스트: `mvn test` (Java 17+, JUnit 5)
- 대용량·메모리 확인: `mvn -q test-compile && java -Xmx64m -cp target/classes:target/test-classes pmtiles.stream.BoundedMemoryDemo /tmp/out.pmtiles 12 rowmajor stream`
  - 인자: `<출력> <maxZoom> <hilbert|rowmajor> <file|stream>`
- 스펙 검사(독립 구현, 의존성 없음): `python3 tools/pmtiles_check.py out.pmtiles [--zxy 97]`
  - `--zxy`는 페이로드가 "z/x/y" 문자열인 데모/테스트 출력에만 사용한다.
- 공식 교차 검증(설치되어 있으면): `pmtiles verify out.pmtiles`, `pmtiles show out.pmtiles` (go-pmtiles)

## 코드 구조 (src/main/java/pmtiles/stream)
- `PmtilesWriter`: 공개 API와 Builder. 쓰기 경로, finish(정렬·재배치·디렉터리·헤더), 수명주기를 담당한다.
- `EntryStore` / `EntryCursor`: 24바이트 엔트리 레코드를 디스크로 스필하고, 청크 quicksort와 k-way 병합을 한다.
- `DirectoryBuilder`: root와 1단계 leaf를 만든다. leaf 크기 규칙은 go-pmtiles와 같다.
- `Directory`: 컬럼형 엔트리 버퍼이자 v3 디렉터리 직렬화기.
- `DedupCache`: SHA-256 128비트 키를 쓰는 LRU.
- `Header`: 127바이트 헤더.
- `TileId`: Hilbert tile ID.
- 공개 타입: `PmtilesWriter`, `TileId`, `TileCoord`, `TileType`, `Compression`, `Codec`, `WriteResult`.

## 지켜야 할 불변 조건
- 힙 사용량이 타일 수에 비례하면 안 된다. 메모리를 쓰는 것을 추가할 때는 크기 상한이나 디스크 스필이 있어야 한다. 알려진 예외 두 가지는 DESIGN.md에 있다.
- 파일 I/O는 `java.io`를 쓴다(RandomAccessFile, FileInput/OutputStream). `Files.newInputStream`/`newOutputStream`과 FileChannel은 스레드 interrupt 시 닫히므로 쓰기 경로에 쓰지 않는다. 예외는 finish의 `copyData`뿐이다.
- 런타임 의존성을 추가하지 않는다. 다른 압축 코덱은 `Codec`으로 사용자가 연결한다.
- 출력 레이아웃을 바꾸지 않는 리팩터링이라면, 바꾸기 전과 후의 출력이 바이트 단위로 같은지 확인한다(파일·스트림 모드 × 정렬·row-major·셔플 입력).
- 동작을 바꿨다면 테스트와 함께 README.md와 docs/DESIGN.md도 갱신한다.

## 이어서 할 일
1. `mvn test`를 실제로 돌려 본다. 이전 작업 환경에서는 Maven Central이 막혀 있어서 javac와 JUnit 스텁으로만 실행했다.
2. go-pmtiles로 `pmtiles verify`를 돌려 교차 검증한다. 아직 한 번도 하지 않았다.
3. 남은 일 후보: 파일 모드에서 비정렬 입력을 clustered로 다시 쓰는 옵션, MVT `vector_layers` 검사 옵션, S3 multipart 예제.
