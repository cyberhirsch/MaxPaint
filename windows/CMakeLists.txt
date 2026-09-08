cmake_minimum_required(VERSION 3.20)
project(MaxPaint CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
set(GLFW_BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
FetchContent_Declare(glfw
    GIT_REPOSITORY https://github.com/glfw/glfw.git
    GIT_TAG 3.4)
FetchContent_MakeAvailable(glfw)

add_executable(maxpaint src/main.cpp)
target_link_libraries(maxpaint PRIVATE glfw)

# The app runs the Android GLES compute shaders verbatim (the loader adapts
# the version header); copy them next to the executable.
add_custom_command(TARGET maxpaint POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
        ${CMAKE_CURRENT_SOURCE_DIR}/../android/app/src/main/assets/shaders
        $<TARGET_FILE_DIR:maxpaint>/shaders)
