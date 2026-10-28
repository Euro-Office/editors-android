# The engine for libeditors: editors-engine with its JNI binding (lib.editors.base.engine.NativeEngine),
# one library (libeoengine.so). Gradle passes EDITORS_ENGINE_DIR and core's directories.
cmake_minimum_required(VERSION 3.22)
project(eo_editors_android CXX)

set(EDITORS_ENGINE_DIR "" CACHE PATH "editors-engine checkout")
add_subdirectory("${EDITORS_ENGINE_DIR}" editors-engine)

target_sources(eoengine PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/DocumentJni.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/SpellerJni.cpp"
)
target_link_libraries(eoengine PRIVATE jnigraphics log)
