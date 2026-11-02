# pmtiles-stream-writer

> 이 레포의 코드는 AI로 생성했으며, 사람이 코드를 검토하지 않았습니다.

타일을 메모리에 모으지 않고, MBTiles 중간 변환도 없이 **PMTiles v3 아카이브를 스트리밍으로 쓰는** Java 라이브러리입니다.

- 힙 사용량이 타일 개수와 무관하게 일정합니다. (z0–12 전체, 2,236만 타일을 `-Xmx64m`으로 기록)
- 타일 바이트는 받는 즉시 최종 위치에 한 번만 씁니다. 타일 데이터의 임시 사본을 만들지 않습니다.
- 런타임 의존성이 없습니다. (JDK 17+)
- 입력 순서는 자유입니다. 순서가 뒤섞인 입력은 외부 정렬(external merge sort)로 처리합니다.
- 중복 타일 제거(LRU 해시 캐시)와 run-length 인코딩, leaf 디렉터리 분할을 자동으로 처리합니다.
- 출력 대상: 로컬 파일(`Path`), 오브젝트 스토리지 multipart 업로드(`MultipartUpload`: S3 등).

## 빠른 시작

```java
try (PmtilesWriter w = PmtilesWriter.builder()
        .tileType(TileType.MVT)
        .tileCompression(Compression.GZIP)          // 타일 바이트가 이미 gzip이라는 선언
        .metadata("{\"vector_layers\":[{\"id\":\"roads\",\"fields\":{}}]}")
        .open(Path.of("out.pmtiles"))) {

    for (Tile t : generator) {
        w.write(t.z(), t.x(), t.y(), t.gzippedMvt());
    }
    WriteResult r = w.finish();   // 반드시 호출
    System.out.println(r);
}
```

`finish()`를 호출하지 않고 `close()`되면(예: 루프 안에서 예외 발생) 미완성 출력을 폐기합니다. 파일은 지우고 multipart 업로드는 abort합니다. 헤더 없는 깨진 아카이브가 남지 않습니다.

`write()`는 여러 스레드에서 동시에 호출해도 됩니다. 쓰기 경로는 `java.io`를 써서, 작업 취소 등으로 생산자 스레드가 interrupt돼도 아카이브가 망가지지 않습니다.

### S3로 바로 업로드

로컬 디스크에 타일 데이터를 두지 않고 쓰는 동안 바로 올립니다. 라이브러리는 S3 SDK에 의존하지 않으므로 `MultipartUpload` 인터페이스 세 메서드를 직접 구현합니다. 아래는 AWS SDK v2 예시입니다(이 레포에서 실제 S3로 실행해 보지는 않았습니다).

```java
String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
List<CompletedPart> parts = new ArrayList<>();

MultipartUpload upload = new MultipartUpload() {
    public void uploadPart(int n, byte[] data, int len) {
        UploadPartResponse res = s3.uploadPart(
            r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(n),
            RequestBody.fromBytes(Arrays.copyOf(data, len)));   // data는 반환 후 재사용됨
        parts.add(CompletedPart.builder().partNumber(n).eTag(res.eTag()).build());
    }
    public void complete() {
        parts.sort(Comparator.comparing(CompletedPart::partNumber));
        s3.completeMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId)
            .multipartUpload(m -> m.parts(parts)));
    }
    public void abort() {
        s3.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId));
    }
};

try (PmtilesWriter w = PmtilesWriter.builder()
        .tileType(TileType.PNG).tileCompression(Compression.NONE)
        .open(upload, 16 << 20)) {   // 16 MiB 파트: 최대 약 160 GB
    ...
    w.finish();
}
```

- 파트 2, 3, …은 쓰는 동안 차는 대로 올라가고, 파트 1(헤더·루트·첫 타일들)은 `finish()`에서 마지막에 올라갑니다. S3는 파트 번호 순서로 조립하므로 업로드 순서는 상관없습니다.
- 마지막 파트를 뺀 모든 파트는 정확히 `partSize` 바이트입니다. S3 파트 최소 크기는 5 MiB이고, 파트는 최대 10,000개라서 아카이브 최대 크기는 `10,000 × partSize`입니다.
- 힙은 파트 2개분(`2 × partSize`)을 추가로 씁니다.
- 결과 객체는 `open(Path)`로 쓴 파일과 바이트 단위로 같습니다.

### zstd / brotli 내부 압축

내장 코덱은 `NONE`과 `GZIP`입니다. 다른 코덱은 원하는 라이브러리로 연결하면 됩니다.

```java
builder.internalCompression(Compression.ZSTD, Zstd::compress);   // com.github.luben:zstd-jni
```

타일 바이트는 라이브러리가 절대 재압축하지 않습니다. `tileCompression(...)`은 헤더에 기록할 선언일 뿐입니다.

## 동작 방식

PMTiles는 헤더와 루트 디렉터리가 파일 맨 앞(첫 16 KiB)에 있어야 하는데, 디렉터리는 모든 타일을 다 써야 확정됩니다. 기존 라이브러리가 전부 메모리에 올리거나 MBTiles(SQLite)를 거치는 이유가 이것입니다. 이 라이브러리는 앞 16 KiB를 비워 두고 타일을 그 뒤에 바로 쓴 다음, 앞부분을 맨 마지막에 채웁니다.

```
| header | root | (빈 공간) | tile data ...........| leaf dirs | metadata |
0        127               16384
                             ← write() 중에 기록 →  ←  finish()에 기록  →
앞 16 KiB(header + root)는 finish()의 마지막 단계에 기록
```

- 로컬 파일: 16384번 바이트부터 쓰고, 마지막에 0번 위치로 돌아가 앞 16 KiB를 씁니다.
- multipart 업로드: 16384번 바이트 이후를 파트 단위로 올리고, 앞 16 KiB가 들어가는 파트 1을 마지막에 올립니다.

메타데이터는 크기와 관계없이 항상 맨 뒤에 둡니다. 아카이브 길이가 `16384 + metadata + leaf + tile data`가 되어 go-pmtiles `pmtiles verify`를 통과하며, Planetiler와 같은 배치입니다.

**디렉터리 엔트리** (타일당 24바이트) 는 고정 크기 청크에 모았다가 가득 차면 정렬해서 run 파일로 내보냅니다. 입력이 정렬돼 있으면 run을 이어 붙이기만 하고, 아니면 k-way 병합합니다(run이 128개를 넘으면 다단계 병합). leaf 디렉터리도 파일로 기록하므로 디렉터리 총량이 수 GB여도 메모리에 올라오지 않습니다. leaf 크기는 go-pmtiles와 같은 방식으로 정합니다: `max(4096, n/3500)`에서 시작해 루트가 16,257바이트에 들어갈 때까지 20%씩 키웁니다.

### 메모리 구성 (기본값)

| 항목 | 크기 | 설정 |
|---|---|---|
| 엔트리 정렬 청크 | 최대 1,048,576개 × 24 B ≈ 24 MiB | `sortChunkEntries` |
| 중복 제거 LRU | 100,000개 × 약 120 B ≈ 12 MiB | `dedupCacheEntries` (0이면 끔) |
| 쓰기 버퍼 | 1 MiB | — |
| multipart 파트 버퍼 | `2 × partSize` (multipart일 때만) | `open(upload, partSize)` |
| 병합 버퍼 | run당 64 KiB × 최대 128개 | — |
| leaf 디렉터리 1개 | `n/3500`개 × 24 B (엔트리 10억 개당 약 7 MiB) | — |

중복 제거는 SHA-256 앞 128비트를 키로 쓰는 LRU입니다. 실제 타일셋의 반복 타일(바다, 빈 땅, 단색)은 종류는 적고 반복이 많아서 작은 LRU로도 거의 다 잡힙니다.

타일 수에 비례하지 않는 예외가 하나 있습니다. **leaf 크기**는 go-pmtiles와 같은 규칙이라 전체 엔트리 수에 비례합니다. 보통 규모에서는 작지만, 엔트리가 수백억 개면 수백 MiB가 됩니다.

### 디스크 사용량

- 엔트리 스필(타일 엔트리당 24 B)과 leaf 디렉터리만 임시 파일로 씁니다. 타일 데이터는 임시로 저장하지 않습니다.
- 기본 임시 경로는 로컬 파일이면 출력 파일과 같은 디렉터리, multipart면 `java.io.tmpdir`입니다. `tempDirectory(...)`로 바꿀 수 있습니다.
- 로컬 파일 출력이면 여기에 결과 파일 크기가 더해집니다.

## 입력 순서와 clustered

| 입력 순서 | 결과 |
|---|---|
| tile ID(Hilbert) 오름차순 | clustered, 추가 비용 없음 |
| 그 외 (row-major, 병렬 생성 등) | 유효한 아카이브, **clustered 아님** |

타일 데이터는 한 번 쓰면 옮기지 않으므로, 결과 배치는 입력 순서를 따릅니다. clustered가 아니어도 모든 리더가 읽을 수 있지만, 타일 데이터가 공간적으로 흩어져서 HTTP range 요청 효율이 떨어지고 leaf 디렉터리가 커집니다(오프셋이 연속이 아니라 varint가 길어짐). clustered 결과가 필요하면 tile ID 순서로 생성하세요.

```java
// zoom 단위로 Hilbert 순서 순회
for (int z = minZoom; z <= maxZoom; z++) {
    for (long id = TileId.zoomStart(z); id < TileId.zoomStart(z + 1); id++) {
        TileCoord c = TileId.toCoord(id);
        byte[] tile = render(c.z(), c.x(), c.y());
        if (tile != null) w.writeTileId(id, tile);
    }
}
```

## API 요약

| 메서드 | 설명 |
|---|---|
| `open(Path)` | 로컬 파일에 쓰기 |
| `open(MultipartUpload, partSize)` | multipart 업로드로 쓰기 |
| `write(z, x, y, bytes)` | 타일 기록. 스레드 안전(해시 계산은 락 밖에서 수행) |
| `writeTileId(id, bytes)` | PMTiles tile ID로 기록 |
| `setMetadata(json)` | `finish()` 전까지 언제든 메타데이터 교체 |
| `finish()` → `WriteResult` | 아카이브 완성. 성공 후 재호출 시 같은 결과 반환 |
| `close()` | 리소스 정리. `finish()` 성공 전이면 출력 폐기(파일 삭제 / 업로드 abort) |
| `TileId.toTileId / toCoord / zoomStart` | Hilbert tile ID 변환 유틸리티 |

Builder 옵션: `tileType`, `tileCompression`, `internalCompression`, `metadata`, `bounds`, `center`, `tempDirectory`, `dedupCacheEntries`, `sortChunkEntries`.

헤더의 min/max zoom은 실제 기록된 타일에서 계산합니다. bounds를 지정하지 않으면 최대 줌 타일들의 범위를 쓰고, center는 bounds 중앙과 최소 줌을 씁니다.

## 검증

```
mvn test
```

- 테스트 27개: 스펙의 tile ID 예시값, 디렉터리 바이트 단위 인코딩, 파일·multipart × 정렬/비정렬 입력, multipart 결과와 파일 결과의 바이트 일치(파트 1개·여러 개·최소 크기), 업로드 abort, 중복 제거 + RLE, leaf 디렉터리, 다단계 병합, 중복 타일 거부, 실패 시 정리, 동시 쓰기, interrupt된 생산자 스레드, 버퍼보다 큰 타일, z31 등.
- 대용량: z0–12 전체(22,369,621 타일)를 multipart(8 MiB 파트) × Hilbert·row-major 순서로 `-Xmx64m`에서 기록했습니다. 파일 출력은 이전 버전에서 `-Xmx48m`으로 기록했고, 현재 버전의 파일 출력은 리팩터링 전과 바이트 단위로 같습니다(z0–10 확인).
  ```
  java -Xmx64m -cp target/classes:target/test-classes pmtiles.stream.BoundedMemoryDemo out.pmtiles 12 rowmajor multipart
  ```
  (Windows에서는 클래스패스 구분자로 `;`를 씁니다.) `multipart`는 파트를 S3가 조립하는 위치에 파일로 써서 결과를 파일처럼 검사할 수 있게 합니다.
- 의존성 없는 스펙 검사기(Java 코드와 별개로 스펙만 보고 구현): `python3 tools/pmtiles_check.py out.pmtiles`
- 공식 도구 [go-pmtiles](https://github.com/protomaps/go-pmtiles) v1.31.2의 `pmtiles verify`로 z0–10 파일·multipart 출력과 z0–12 multipart 출력을 검증했습니다.
  ```
  pmtiles verify out.pmtiles
  pmtiles show out.pmtiles
  ```

## 제한 사항 / 앞으로 할 일

- leaf 디렉터리는 한 단계만 만듭니다(스펙 권고와 같음).
- 메타데이터 JSON은 객체인지만 확인합니다. MVT의 `vector_layers` 필수 여부는 검사하지 않습니다.
- 같은 타일을 두 번 쓰면 `finish()`에서 `IllegalStateException`이 납니다(어느 타일인지 메시지에 포함).
- 빈 타일(길이 0)은 스펙상 허용되지 않아 거부합니다. 빈 타일은 쓰지 말고 건너뛰세요.
- 비정렬 입력을 clustered로 만드는 옵션은 없습니다.
- HTTP 응답이나 파이프처럼 앞으로 돌아갈 수 없고 파트 단위 조립도 안 되는 출력은 지원하지 않습니다. 이런 출력은 구조상 타일 데이터 전체를 어딘가에 모아 둬야 하므로, 로컬 파일로 쓴 뒤 보내세요.
