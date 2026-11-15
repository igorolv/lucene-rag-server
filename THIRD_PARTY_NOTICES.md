# Third-party Notices

This project is licensed under the Apache License, Version 2.0. Its dependencies are licensed by
their respective owners under their own terms. The release jar bundles the runtime dependencies
below (156 libraries), and each keeps its own license.

This file is informational, taken from the dependency POMs on 2026-11-15. The dependency POMs and
upstream license files remain authoritative.

## Runtime Dependencies

| Component | License |
|---|---|
| Spring Boot, Spring Framework, Spring AI | Apache License 2.0 |
| Apache Lucene, PDFBox, Tika, POI, XMLBeans, James Mime4j, Tomcat (embedded), Log4j API | Apache License 2.0 |
| Apache Commons Codec, Collections, Compress, CSV, IO, Lang, Logging, Math | Apache License 2.0 |
| Jackcess, Jackcess Encrypt, java-libpst, SparseBitSet | Apache License 2.0 |
| Jackson 2 and 3, Reactor, Micrometer, SnakeYAML, SnakeYAML Engine, ClassMate, ITU, JSpecify, Gson, Error Prone annotations, JetBrains annotations | Apache License 2.0 |
| victools JSON Schema Generator, networknt JSON Schema Validator, Swagger Annotations | Apache License 2.0 |
| OpenAI Java SDK, OkHttp, Okio, Kotlin standard library and reflection | Apache License 2.0 |
| DJL API and Hugging Face tokenizers binding (Deep Java Library) | Apache License 2.0 |
| ONNX Runtime | MIT License |
| jsoup | MIT License |
| Bouncy Castle | Bouncy Castle Licence (an MIT-style license) |
| CommonMark Java | BSD 2-Clause License |
| curvesapi | BSD License |
| Jakarta XML Binding API, JAXB runtime, istack commons, Jakarta Activation API, Angus Activation | Eclipse Distribution License 1.0 |
| MCP Java SDK, JTokkit | MIT License |
| SLF4J | MIT License |
| Reactive Streams | MIT-0 |
| ANTLR 4 runtime | BSD 3-Clause License |
| ANTLR 3 runtime, StringTemplate 4 | BSD License |
| HdrHistogram | CC0 or BSD 2-Clause License |
| Logback | Eclipse Public License 2.0 or GNU LGPL 2.1 |
| JNA | GNU LGPL 2.1 or later, or Apache License 2.0 |
| Jakarta Annotations API | Eclipse Public License 2.0 or GPL 2 with Classpath Exception |

## Native Code Inside the Jar

- `onnxruntime` carries the ONNX Runtime libraries for Linux, macOS and Windows, under the MIT
  License; the third-party notices of ONNX Runtime itself are published with its source.
- `tokenizers` (DJL) carries the native build of the Hugging Face `tokenizers` library (Apache
  License 2.0). Its Windows build also carries the MinGW runtime: `libgcc_s_seh-1.dll` and
  `libstdc++-6.dll`, under the GNU GPL 3 with the GCC Runtime Library Exception, and
  `libwinpthread-1.dll`, under a permissive license of the MinGW-w64 project (to verify against
  the files of that DJL release).

## Embedding Models

No model is bundled. `rag-model fetch` downloads one when the operator runs it, and a model keeps
its own license: `intfloat/multilingual-e5-small` and `intfloat/multilingual-e5-base` are under the
MIT License, as their Hugging Face repositories state. A model fetched with `--repo` is under the
license its repository states.

## Test Dependencies

These are used only to build and test the project and are not in the release jar.

| Component | License |
|---|---|
| Spring Boot Test, Spring Test | Apache License 2.0 |
| JUnit 5 | Eclipse Public License 2.0 |
| AssertJ, Mockito, Byte Buddy, Objenesis, Awaitility, OpenTest4J | Apache License 2.0 |
| JSONassert, android-json, json-path, json-smart, accessors-smart, XMLUnit | Apache License 2.0 |
| Hamcrest | BSD 3-Clause License |
| Jakarta XML Binding API, Jakarta Activation API | Eclipse Distribution License 1.0 |
