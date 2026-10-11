# swiss-ephemeris-service

A stateless gRPC service for astronomical positions, built on the [Swiss Ephemeris](https://www.astro.com/swisseph/)
by Astrodienst AG. Every request carries all of its inputs, so responses can be cached by request and the
service can run as any number of identical replicas.

The contract is [`ephemeris.proto`](src/main/proto/me/lagna/ephemeris/v1/ephemeris.proto)
(package `me.lagna.ephemeris.v1`):

| RPC               | What it returns                                                                    |
|-------------------|------------------------------------------------------------------------------------|
| `GetPositions`    | Positions and speeds of several bodies at several instants, tropical or sidereal   |
| `StreamPositions` | Positions sampled at a fixed step over a range, streamed in chunks                 |
| `GetAyanamsa`     | Ayanamsa values for several instants                                                |
| `GetHouses`       | Ascendant, MC, ARMC, Vertex and house cusps (8 house systems)                       |
| `GetRiseSet`      | Rise, set and meridian transits of a body                                           |
| `FindCrossings`   | Exact instants a body crosses a grid or list of longitudes (signs, nakshatras, …)  |
| `FindStations`    | Exact instants a body turns retrograde or direct                                    |
| `GetInfo`         | Engine version, supported date range, limits and the source code location          |

Bodies: Sun to Pluto, mean and true node, mean apogee, Chiron. Supported instants: 1800-01-02 to 2400-01-01
(the range of the bundled `.se1` files). The service refuses to start if the files are missing instead of
silently falling back to the less precise Moshier model.

## Build and run

Needs JDK 25, a C compiler and `curl`. The build fetches the pinned Swiss Ephemeris sources and the
`.se1` files, verifies each against [`native/swisseph.sha256`](native/swisseph.sha256) and compiles
`libswe`, which the service calls through the Java Foreign Function & Memory API.

```bash
./gradlew build       # libswe, code generation, tests
./gradlew perfTest    # timing of crossing searches with and without speed (build/perf/results.txt)
./gradlew run         # gRPC on :50051
grpcurl -plaintext -d '{"instants":["2000-01-01T12:00:00Z"],"bodies":["SUN","MOON"],
  "zodiac":{"sidereal":{"ayanamsa":"LAHIRI"}}}' localhost:50051 me.lagna.ephemeris.v1.Ephemeris/GetPositions
```

Server reflection and the standard gRPC health service are enabled.

## Configuration

Environment variables (system properties in brackets, used by Gradle runs and tests):

| Variable           | Default                       | Meaning                                                |
|--------------------|-------------------------------|--------------------------------------------------------|
| `PORT`             | `50051`                       | gRPC port                                               |
| `SWE_LIB`          | required (`swe.lib`)          | Path to `libswe.so` / `libswe.dylib`                    |
| `SWE_EPHE`         | required (`swe.ephe`)         | Directory with the `.se1` files                         |
| `SWE_THREADS`      | CPU count (1 on macOS)        | libswe worker threads                                   |
| `MAX_ITEMS`        | `10000`                       | Limit per request: instants × bodies, queries, results  |
| `MAX_STREAM_ITEMS` | `1000000`                     | Limit per stream: samples × bodies                      |
| `CACHE_MAX_MB`     | `64`                          | In-process cache of search results; `0` disables it     |
| `SEARCH_WITHOUT_SPEED` | `true`                    | Crossing searches compute longitudes without `SEFLG_SPEED` |
| `SOURCE_URL`       | this repository               | Reported by `GetInfo`                                   |

## Design notes

- **Threads.** libswe keeps its settings in thread-local storage, so all calls run on a fixed pool of
  platform threads, and each call sets the modes it needs (sidereal mode, topocentric observer). A whole
  request, or a whole search, runs in one session on one worker.
- **Searches.** Stations are sign changes of the longitude speed, found by sampling and refined by
  bisection. Between stations the longitude is monotonic, so every target longitude is crossed once and is
  refined by bisection to about 0.1 s.
- **Searches without speed.** Crossing searches only need longitudes, so by default they call libswe without
  `SEFLG_SPEED` (about 5 µs instead of 9 µs per call); station searches still use the speed. Over 2000–2050,
  all 14 bodies, tropical and Lahiri, sign and nakshatra grids, the 69,397 crossings found both ways were
  identical to the millisecond, and calls with speed dropped from 4.7 to 1.6 million. Set
  `SEARCH_WITHOUT_SPEED=false` to always request speed.
- **Cache.** `ResultCache` is a small interface; `CaffeineResultCache` keeps `FindCrossings` and
  `FindStations` results in memory. A shared cache can implement the same interface using
  `CacheKey.digest()` (SHA-256 of the method and the deterministically serialised request).
- **Time.** Instants are UTC and are used as UT1; the difference is under a second.

## License

GNU Affero General Public License v3.0 or later, see [LICENSE](LICENSE). The Swiss Ephemeris is
© Astrodienst AG and is used under its AGPL licence option. If you run a modified version of this service
for others, section 13 of the AGPL requires you to offer them its source code.
