LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE     := mslxvm
LOCAL_SRC_FILES  := mslxvm.c
LOCAL_LDLIBS     := -llog -ldl
LOCAL_CFLAGS     := -O2 -Wall
include $(BUILD_SHARED_LIBRARY)
