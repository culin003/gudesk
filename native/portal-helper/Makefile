# gudesk-portal-helper 构建脚本
# 前提：gcc + pkg-config + libpipewire-0.3 开发包（Debian/Ubuntu: libpipewire-0.3-dev）
# 仅 Linux 平台构建（Windows/macOS 打包不含本目录产物）

CC ?= cc
CFLAGS ?= -O2 -Wall -Wextra
PKGS := libpipewire-0.3
TARGET := gudesk-portal-helper

CFLAGS += -std=c11 $(shell pkg-config --cflags $(PKGS))
LDLIBS += $(shell pkg-config --libs $(PKGS))

.PHONY: all clean

all: $(TARGET)

$(TARGET): portal_helper.c
	$(CC) $(CFLAGS) -o $@ $< $(LDLIBS)

clean:
	rm -f $(TARGET)
