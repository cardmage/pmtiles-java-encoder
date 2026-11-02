# pmtiles-stream-writer

타일을 메모리에 모으지 않고, MBTiles 중간 변환도 없이 **PMTiles v3 아카이브를 스트리밍으로 쓰는** Java 라이브러리입니다.

- 힙 사용량이 타일 개수와 무관하게 일정합니다. (z0–12 전체, 2,236만 타일을 `-Xmx48m`으로 기록)
- 런타임 의존성이 없습니다. (JDK 17+)
- 입력 순서는 자유입니다. 순서가 뒤섞인 입력은 외부 정렬(external merge sort)로 처리합니다.
- 중복 타일 제거(LRU 해시 캐시)와 run-length 인코딩, leaf 디렉터리 분할을 자동으로 처리합니다.
- 출력 대상: 파일(`Path`)과 비탐색형 스트림(`OutputStream`: S3 multipart, HTTP 응답, 파이프 등).

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

파일 모드에서 `finish()`를 호출하지 않고 `close()`되면(예: 루프 안에서 예외 발생) 미완성 파일을 지웁니다. 헤더 없는 깨진 아카이브가 남지 않습니다.

`write()`는 여러 스레드에서 동시에 호출해도 됩니다. 쓰기 경로는 `java.io`를 써서, 작업 취소 등으로 생산자 스레드가 interrupt돼도 아카이브가 망가지지 않습니다. 단, `finish()`를 실행 중인 스레드를 interrupt하면 `finish()`가 중단될 수 있습니다.

### 스트림 출력 (S3 multipart 등)

```java
try (OutputStream s3 = openMultipartUpload(...);
     PmtilesWriter w = PmtilesWriter.builder()
         .tileType(TileType.PNG).tileCompression(Compression.NONE)
         .tempDirectory(Path.of("/mnt/scratch"))   // 타일 데이터가 여기에 임시 저장됨
         .open(s3)) {
    ...
    w.finish();   // 이 시점에 헤더→루트→메타데이터→leaf→타일 순서로 스트림에 기록
}
```

스트림 모드에서는 `finish()` 전까지 스트림에 아무것도 쓰지 않습니다. 하지만 `finish()` 도중 실패하면 이미 보낸 바이트는 되돌릴 수 없습니다. 이 경우 multipart upload를 complete하지 말고 abort하세요.

### zstd / brotli 내부 압축

내장 코덱은 `NONE`과 `GZIP`입니다. 다른 코덱은 원하는 라이브러리로 연결하면 됩니다.

```java
builder.internalCompression(Compression.ZSTD, Zstd::compress);   // com.github.luben:zstd-jni
```

타일 바이트는 라이브러리가 절대 재압축하지 않습니다. `tileCompression(...)`은 헤더에 기록할 선언일 뿐입니다.

## 동작 방식

PMTiles는 헤더와 루트 디렉터리가 파일 맨 앞(첫 16 KiB)에 있어야 하는데, 디렉터리는 모든 타일을 다 써야 확정됩니다. 기존 라이브러리가 전부 메모리에 올리거나 MBTiles(SQLite)를 거치는 이유가 이것입니다. 이 라이브러리는 이 문제를 다음과 같이 풉니다.

**파일 모드** `open(Path)` — 타일 바이트를 받는 즉시 출력 파일의 16384번 바이트부터 씁니다. 각 타일 바이트는 디스크에 한 번만 기록됩니다.

```
| header | root | metadata* | (빈 공간) | tile data ...........| leaf dirs | metadata* |
0        127                 16384
                                         ← write() 중에 기록 →  ← finish()에 기록 →
* 메타데이터가 첫 16 KiB에 들어가면 앞쪽에, 아니면 맨 뒤에 둡니다.
```

**스트림 모드** `open(OutputStream)` — 타일을 임시 파일에 모았다가 `finish()`에서 표준 순서(header, root, metadata, leaves, tile data)로 한 번에 흘려 보냅니다. 입력이 tile ID 순서가 아니었다면 복사하면서 tile ID 순서로 재배치하므로 결과는 항상 clustered입니다.

**디렉터리 엔트리** (타일당 24바이트) 는 고정 크기 청크에 모았다가 가득 차면 정렬해서 run 파일로 내보냅니다. 입력이 정렬돼 있으면 run을 이어 붙이기만 하고, 아니면 k-way 병합합니다(run이 128개를 넘으면 다단계 병합). leaf 디렉터리도 파일로 기록하므로 디렉터리 총량이 수 GB여도 메모리에 올라오지 않습니다. leaf 크기는 go-pmtiles와 같은 방식으로 정합니다: `max(4096, n/3500)`에서 시작해 루트가 16,257바이트에 들어갈 때까지 20%씩 키웁니다.

### 메모리 구성 (기본값)

| 항목 | 크기 | 설정 |
|---|---|---|
| 엔트리 정렬 청크 | 최대 1,048,576개 × 24 B ≈ 24 MiB | `sortChunkEntries` |
| 중복 제거 LRU | 100,000개 × 약 120 B ≈ 12 MiB | `dedupCacheEntries` (0이면 끔) |
| 쓰기 버퍼 | 1 MiB | — |
| 병합 버퍼 | run당 64 KiB × 최대 128개 | — |
| leaf 디렉터리 1개 | `n/3500`개 × 24 B (엔트리 10억 개당 약 7 MiB) | — |

중복 제거는 SHA-256 앞 128비트를 키로 쓰는 LRU입니다. 실제 타일셋의 반복 타일(바다, 빈 땅, 단색)은 종류는 적고 반복이 많아서 작은 LRU로도 거의 다 잡힙니다.

타일 수에 비례하지 않는 예외가 두 가지 있습니다.
- **leaf 크기**는 go-pmtiles와 같은 규칙이라 전체 엔트리 수에 비례합니다. 보통 규모에서는 작지만, 엔트리가 수백억 개면 수백 MiB가 됩니다.
- **스트림 모드 + 비정렬 입력**일 때는 `finish()`에서 "반복된 내용의 서로 다른 개수"만큼 맵(항목당 약 100 B)을 만듭니다. 재배치에 필요하기 때문입니다. 반복 기록은 쓰는 동안 디스크에 쌓아 두므로, 정렬된 입력이면 이 맵을 아예 만들지 않습니다.

### 디스크 사용량

- 파일 모드: 출력 파일 + 엔트리 스필(타일 엔트리당 24 B) + leaf 디렉터리. 기본 임시 경로는 출력 파일과 같은 디렉터리입니다.
- 스트림 모드: 위에 더해 **타일 데이터 전체의 임시 사본**이 필요합니다. 기본 임시 경로는 `java.io.tmpdir`입니다. 큰 작업에서는 `tempDirectory(...)`로 충분한 공간이 있는 곳을 지정하세요.

## 입력 순서와 clustered

| 입력 순서 | 파일 모드 | 스트림 모드 |
|---|---|---|
| tile ID(Hilbert) 오름차순 | clustered, 추가 비용 없음 | clustered, 추가 비용 없음 |
| 그 외 (row-major, 병렬 생성 등) | 유효한 아카이브, **clustered 아님** | 재배치해서 clustered |

clustered가 아니어도 모든 리더가 읽을 수 있지만, 타일 데이터가 공간적으로 흩어져서 HTTP range 요청 효율이 떨어지고 leaf 디렉터리가 커집니다(오프셋이 연속이 아니라 varint가 길어짐). 파일 모드에서 clustered 결과가 필요하면 tile ID 순서로 생성하는 것이 가장 쌉니다.

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
| `write(z, x, y, bytes)` | 타일 기록. 스레드 안전(해시 계산은 락 밖에서 수행) |
| `writeTileId(id, bytes)` | PMTiles tile ID로 기록 |
| `setMetadata(json)` | `finish()` 전까지 언제든 메타데이터 교체 |
| `finish()` → `WriteResult` | 아카이브 완성. 성공 후 재호출 시 같은 결과 반환 |
| `close()` | 리소스 정리. `finish()` 성공 전이면 출력 폐기 |
| `TileId.toTileId / toCoord / zoomStart` | Hilbert tile ID 변환 유틸리티 |

Builder 옵션: `tileType`, `tileCompression`, `internalCompression`, `metadata`, `bounds`, `center`, `tempDirectory`, `dedupCacheEntries`, `sortChunkEntries`.

헤더의 min/max zoom은 실제 기록된 타일에서 계산합니다. bounds를 지정하지 않으면 최대 줌 타일들의 범위를 쓰고, center는 bounds 중앙과 최소 줌을 씁니다.

## 검증

```
mvn test
```

- 테스트 27개: 스펙의 tile ID 예시값, 디렉터리 바이트 단위 인코딩, 두 모드 × 정렬/비정렬 입력, 중복 제거 + RLE, leaf 디렉터리, 다단계 병합, 중복 타일 거부, 실패 시 정리, 동시 쓰기, interrupt된 생산자 스레드, 버퍼보다 큰 타일, z31 등.
- 대용량: z0–12 전체(22,369,621 타일)를 세 가지 조합(파일+Hilbert 순서, 파일+row-major, 스트림+row-major) 모두 `-Xmx48m`에서 기록했습니다. 이 힙 크기에서는 GC 때문에 느려지므로 기본 설정에서는 64 MiB 이상을 권장합니다. 순서를 뒤섞은 입력을 스트림 모드로 쓴 결과의 타일 데이터는 순서대로 쓴 결과와 바이트 단위로 같습니다.
  ```
  java -Xmx64m -cp target/classes:target/test-classes pmtiles.stream.BoundedMemoryDemo out.pmtiles 12 rowmajor stream
  ```
- 의존성 없는 스펙 검사기(Java 코드와 별개로 스펙만 보고 구현): `python3 tools/pmtiles_check.py out.pmtiles`
- 공식 도구로 교차 검증하려면 [go-pmtiles](https://github.com/protomaps/go-pmtiles)를 쓰세요:
  ```
  pmtiles verify out.pmtiles
  pmtiles show out.pmtiles
  ```

## 제한 사항 / 앞으로 할 일

- leaf 디렉터리는 한 단계만 만듭니다(스펙 권고와 같음).
- 메타데이터 JSON은 객체인지만 확인합니다. MVT의 `vector_layers` 필수 여부는 검사하지 않습니다.
- 같은 타일을 두 번 쓰면 `finish()`에서 `IllegalStateException`이 납니다(어느 타일인지 메시지에 포함).
- 빈 타일(길이 0)은 스펙상 허용되지 않아 거부합니다. 빈 타일은 쓰지 말고 건너뛰세요.
- 파일 모드에서 비정렬 입력을 clustered로 만드는 옵션(완료 시 재작성)은 아직 없습니다.
