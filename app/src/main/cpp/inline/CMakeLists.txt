include_directories(xhook)

add_library(inline SHARED
        inline.cpp bpf_hook.cpp
        xhook/xhook.c
        xhook/xh_core.c
        xhook/xh_elf.c
        xhook/xh_util.c
        xhook/xh_version.c
        )
target_link_libraries(inline ${log-lib} cxx::cxx)
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET inline POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libinline.so")
endif ()
