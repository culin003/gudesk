/*
 * gudesk-portal-helper — Wayland portal 屏幕捕获的 PipeWire 帧消费器
 *
 * 架构（见 .trae/specs/wayland-portal-support/）：
 *   portal D-Bus 会话由 Java 侧 PortalClient 持有（RemoteDesktop+ScreenCast 捆绑
 *   会话 = 单次授权弹窗，输入注入/restore token 亦由 Java 管理）。本 helper 仅负责
 *   消费 PipeWire 流：Java 经 ScreenCast.OpenPipeWireRemote 获得 PipeWire 连接 fd，
 *   通过 unix socket 以 SCM_RIGHTS 发送给本进程，本进程绑定流节点并将 BGRA 帧
 *   以二进制协议写到 stdout。
 *
 * 用法：
 *   gudesk-portal-helper --fd-socket <path> [--node <id>] [--serial <serial>]
 *                        [--max-fps <n>]
 *
 *   --fd-socket  控制用 unix socket 路径（Java 生成，如
 *                $XDG_RUNTIME_DIR/gudesk-portal-<pid>.sock）。helper 绑定并监听，
 *                Java 连接后发送控制消息 + SCM_RIGHTS fd（Java 侧连接失败应短暂
 *                重试，绑定存在毫秒级窗口）。
 *   --node       PipeWire 流节点 ID（legacy 定位方式，Start() 响应流元组首元素）。
 *   --serial     PipeWire object.serial（优先定位方式，流属性 pipewire-serial）。
 *                node 与 serial 至少提供一个（命令行或控制消息均可）。
 *   --max-fps    输出帧率上限（0 = 不限制，默认 0）。
 *
 * 控制消息（recvmsg 数据部分，主机字节序，同机通信）：
 *   struct gd_ctrl {
 *     uint32_t magic;     // CTRL_MAGIC = 0x54434447 ("GDCT")
 *     uint32_t version;   // 1
 *     uint32_t node_id;   // 流节点 ID，0 = 不使用
 *     uint32_t reserved;  // 对齐保留
 *     uint64_t serial;    // object.serial，0 = 不使用
 *   };  // 24 字节
 *   SCM_RIGHTS 辅助数据携带恰好 1 个 fd（PipeWire 连接，所有权移交本进程）。
 *
 * 帧输出协议（stdout，小端，二进制帧流）：
 *   偏移 0   : int64_t pts_ns        // 单调时钟纳秒
 *   偏移 8   : uint32_t magic        // FRAME_MAGIC = 0x31464447 ("GDF1")
 *   偏移 12  : uint16_t version      // 1
 *   偏移 14  : uint16_t flags        // bit0: 全帧提示（portal 流恒为全帧，恒置 1）
 *   偏移 16  : uint32_t width
 *   偏移 20  : uint32_t height
 *   偏移 24  : uint32_t payload_size // = width * height * 4
 *   偏移 28  : BGRA 像素数据（payload_size 字节，已去 stride 对齐、BGRx 已补 alpha）
 *
 *   注意：pts_ns 放在偏移 0 以保证结构体自然对齐无填充，Java 侧按上述偏移解析。
 *
 * 退出码：0 正常结束；1 参数错误；2 控制通道错误；3 PipeWire 错误。
 * 日志一律写 stderr（stdout 为纯二进制帧流）。
 */

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <spa/param/video/format-utils.h>
#include <pipewire/pipewire.h>

#define CTRL_MAGIC 0x54434447u   /* "GDCT" */
#define FRAME_MAGIC 0x31464447u  /* "GDF1" */
#define PROTO_VERSION 1u
#define ACCEPT_TIMEOUT_MS 30000

/** 控制消息（SCM_RIGHTS 数据部分，见文件头注释） */
struct gd_ctrl {
    uint32_t magic;
    uint32_t version;
    uint32_t node_id;
    uint32_t reserved;
    uint64_t serial;
};

/** 帧头（28 字节，packed 消除尾部对齐填充，与 Java 侧 FRAME_HEADER_SIZE 严格一致） */
struct __attribute__((packed)) gd_frame_header {
    int64_t pts_ns;
    uint32_t magic;
    uint16_t version;
    uint16_t flags;
    uint32_t width;
    uint32_t height;
    uint32_t payload_size;
};

/* 协议结构大小与 Java 侧严格一致（防对齐填充引入流错位） */
_Static_assert(sizeof(struct gd_frame_header) == 28, "帧头必须为 28 字节");
_Static_assert(sizeof(struct gd_ctrl) == 24, "控制消息必须为 24 字节");

/** 运行状态 */
struct gd_state {
    struct pw_main_loop *loop;
    struct pw_context *ctx;
    struct pw_core *core;
    struct pw_stream *stream;

    uint32_t node_id;
    uint64_t serial;
    int64_t min_interval_ns;
    int64_t last_emit_ns;

    uint32_t width;
    uint32_t height;
    enum spa_video_format spa_format;
    uint8_t *staging;      /* BGRA 整帧暂存（去 stride / 补 alpha） */
    size_t staging_size;

    uint64_t frames;
    bool was_streaming;

    /* dmabuf/memfd 的 mmap 缓存（同 fd 复用映射，分辨率/缓冲区切换时重映射） */
    struct {
        int fd;
        size_t len;
        uint8_t *ptr;
    } map_cache;
    bool skip_logged; /* 不可映射缓冲区只告警一次 */
};

// ------------------------------------------------------------------
// 工具
// ------------------------------------------------------------------

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000L + ts.tv_nsec;
}

static void log_state(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fputs("[portal-helper] ", stderr);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
}

/** 全量写（处理 EINTR 与部分写），失败返回 -1 */
static int full_write(int fd, const void *buf, size_t len) {
    const uint8_t *p = buf;
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, p + off, len - off);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        off += (size_t)n;
    }
    return 0;
}

// ------------------------------------------------------------------
// 帧输出
// ------------------------------------------------------------------

/**
 * 取缓冲区数据可读地址：MemPtr 直接取；MemFd/DmaBuf 经 mmap（缓存复用，
 * fd 变化时重映射）。KDE/GNOME portal 流常用 DMA-BUF（线性 BGRA 可直接
 * mmap 读取；若后端给瓦片修饰的 dmabuf，mmap 可能失败，此时告警一次并丢帧）。
 */
static const uint8_t *buffer_data(struct gd_state *s, struct spa_data *d) {
    if (d->type == SPA_DATA_MemPtr && d->data != NULL) {
        return (const uint8_t *)d->data + d->chunk->offset;
    }
    if ((d->type == SPA_DATA_MemFd || d->type == SPA_DATA_DmaBuf) && d->fd >= 0) {
        if (s->map_cache.ptr != NULL
                && (s->map_cache.fd != d->fd || s->map_cache.len < d->maxsize)) {
            munmap(s->map_cache.ptr, s->map_cache.len);
            s->map_cache.ptr = NULL;
        }
        if (s->map_cache.ptr == NULL) {
            void *p = mmap(NULL, d->maxsize, PROT_READ, MAP_SHARED, d->fd, 0);
            if (p == MAP_FAILED) {
                if (!s->skip_logged) {
                    log_state("mmap 缓冲区失败（type=%d fd=%d size=%u）: %s",
                            d->type, d->fd, d->maxsize, strerror(errno));
                    s->skip_logged = true;
                }
                return NULL;
            }
            s->map_cache.fd = d->fd;
            s->map_cache.len = d->maxsize;
            s->map_cache.ptr = p;
            log_state("缓冲区 mmap 成功（type=%d fd=%d size=%u）",
                    d->type, d->fd, d->maxsize);
        }
        return (const uint8_t *)s->map_cache.ptr + d->chunk->offset;
    }
    if (!s->skip_logged) {
        log_state("跳过不可映射的缓冲区（type=%d data=%p fd=%d）",
                d->type, d->data, d->fd);
        s->skip_logged = true;
    }
    return NULL;
}

/**
 * 取出当前缓冲区中最新的一帧并输出到 stdout：
 * 排空队列只保留最新帧（消费方慢时自然丢帧，不反压 PipeWire）。
 */
static void emit_frame(struct gd_state *s, struct pw_buffer *b) {
    struct spa_buffer *buf = b->buffer;
    if (buf->n_datas < 1) {
        return;
    }
    struct spa_data *d = &buf->datas[0];
    const uint8_t *src = buffer_data(s, d);
    if (src == NULL) {
        return;
    }
    uint32_t w = s->width;
    uint32_t h = s->height;
    if (w == 0 || h == 0 || s->staging == NULL) {
        return;
    }
    size_t row_bytes = (size_t)w * 4;
    uint32_t stride = (uint32_t)d->chunk->stride;
    if (stride < row_bytes) {
        stride = row_bytes;
    }
    /* 不完整帧（可用行数不足）跳过，等待下一帧 */
    if (d->chunk->size < (uint32_t)stride * h) {
        if (s->frames < 10) {
            log_state("帧 %llu: 不完整（chunk->size=%u < stride %u * %u）",
                    (unsigned long long)s->frames, d->chunk->size, stride, h);
        }
        return;
    }
    if (s->frames < 10) {
        log_state("帧 %llu: type=%d fd=%d offset=%u stride=%u size=%u maxsize=%u %ux%u",
                (unsigned long long)s->frames, d->type, d->fd,
                d->chunk->offset, d->chunk->stride, d->chunk->size, d->maxsize, w, h);
    }

    /* 逐行拷贝去除 stride 对齐；BGRx 时补 alpha=0xFF */
    bool fill_alpha = (s->spa_format != SPA_VIDEO_FORMAT_BGRA);
    for (uint32_t y = 0; y < h; y++) {
        uint8_t *dst = s->staging + (size_t)y * row_bytes;
        memcpy(dst, src + (size_t)y * stride, row_bytes);
        if (fill_alpha) {
            for (uint32_t x = 3; x < row_bytes; x += 4) {
                dst[x] = 0xFF;
            }
        }
    }

    struct gd_frame_header hdr = {
        .pts_ns = now_ns(),
        .magic = FRAME_MAGIC,
        .version = PROTO_VERSION,
        .flags = 0x1,
        .width = w,
        .height = h,
        .payload_size = (uint32_t)(row_bytes * h),
    };
    if (full_write(STDOUT_FILENO, &hdr, sizeof(hdr)) < 0
            || full_write(STDOUT_FILENO, s->staging, row_bytes * h) < 0) {
        log_state("stdout 写入失败: %s（消费方已退出？）", strerror(errno));
        pw_main_loop_quit(s->loop);
        return;
    }
    s->frames++;
    if (s->frames % 300 == 0) {
        log_state("已输出 %" PRIu64 " 帧（%ux%u）", s->frames, w, h);
    }
}

// ------------------------------------------------------------------
// PipeWire 流事件
// ------------------------------------------------------------------

static void on_process(void *userdata) {
    struct gd_state *s = userdata;
    struct pw_buffer *latest = NULL;
    struct pw_buffer *b;
    while ((b = pw_stream_dequeue_buffer(s->stream)) != NULL) {
        if (latest != NULL) {
            pw_stream_queue_buffer(s->stream, latest);
        }
        latest = b;
    }
    if (latest == NULL) {
        return;
    }
    bool throttled = s->min_interval_ns > 0
            && now_ns() - s->last_emit_ns < s->min_interval_ns;
    if (!throttled) {
        s->last_emit_ns = now_ns();
        emit_frame(s, latest);
    }
    pw_stream_queue_buffer(s->stream, latest);
}

static void on_state_changed(void *userdata, enum pw_stream_state old,
        enum pw_stream_state state, const char *error) {
    struct gd_state *s = userdata;
    log_state("流状态: %s -> %s%s%s",
            pw_stream_state_as_string(old), pw_stream_state_as_string(state),
            error != NULL ? " (" : "", error != NULL ? error : "");
    if (state == PW_STREAM_STATE_STREAMING) {
        s->was_streaming = true;
    } else if (state == PW_STREAM_STATE_ERROR) {
        pw_main_loop_quit(s->loop);
    } else if (s->was_streaming && state == PW_STREAM_STATE_UNCONNECTED) {
        log_state("流已被服务端断开，退出");
        pw_main_loop_quit(s->loop);
    }
}

static void on_param_changed(void *userdata, uint32_t id, const struct spa_pod *param) {
    struct gd_state *s = userdata;
    if (param == NULL || id != SPA_PARAM_Format) {
        return;
    }
    struct spa_video_info_raw info;
    memset(&info, 0, sizeof(info));
    if (spa_format_video_raw_parse(param, &info) < 0) {
        log_state("无法解析视频格式参数");
        return;
    }
    s->width = info.size.width;
    s->height = info.size.height;
    s->spa_format = info.format;
    if (s->spa_format != SPA_VIDEO_FORMAT_BGRA
            && s->spa_format != SPA_VIDEO_FORMAT_BGRx) {
        log_state("不支持的像素格式 %d（仅支持 BGRA/BGRx）", s->spa_format);
        pw_main_loop_quit(s->loop);
        return;
    }
    size_t need = (size_t)s->width * 4 * s->height;
    if (s->staging_size < need) {
        free(s->staging);
        s->staging = malloc(need);
        s->staging_size = need;
        if (s->staging == NULL) {
            log_state("暂存缓冲分配失败（%zu 字节）", need);
            pw_main_loop_quit(s->loop);
            return;
        }
    }
    log_state("视频格式: %ux%u %s", s->width, s->height,
            s->spa_format == SPA_VIDEO_FORMAT_BGRA ? "BGRA" : "BGRx");
}

static const struct pw_stream_events STREAM_EVENTS = {
    PW_VERSION_STREAM_EVENTS,
    .state_changed = on_state_changed,
    .param_changed = on_param_changed,
    .process = on_process,
};

static void on_signal(void *data, int sig) {
    struct gd_state *s = data;
    log_state("收到信号 %d，退出", sig);
    pw_main_loop_quit(s->loop);
}

// ------------------------------------------------------------------
// 控制通道（接收 SCM_RIGHTS 传递的 PipeWire fd）
// ------------------------------------------------------------------

/** 等待控制连接期间的 sock_path（供早期信号处理器清理残留文件） */
static const char *g_ctrl_sock_path;

/** 早期信号处理器：等待连接阶段被杀时清理 socket 文件（仅用异步信号安全函数） */
static void ctrl_early_signal_handler(int sig) {
    if (g_ctrl_sock_path != NULL) {
        unlink(g_ctrl_sock_path);
    }
    _exit(128 + sig);
}

/**
 * 在 sock_path 上监听并接收一次控制消息：
 * 返回 0 且 *pw_fd 为 PipeWire 连接 fd（所有权移交调用方）。
 */
static int ctrl_receive(const char *sock_path, struct gd_ctrl *ctrl, int *pw_fd) {
    g_ctrl_sock_path = sock_path;
    signal(SIGINT, ctrl_early_signal_handler);
    signal(SIGTERM, ctrl_early_signal_handler);
    int lfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (lfd < 0) {
        log_state("创建控制 socket 失败: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", sock_path)
            >= (int)sizeof(addr.sun_path)) {
        log_state("控制 socket 路径过长: %s", sock_path);
        close(lfd);
        return -1;
    }
    unlink(sock_path); /* 清理可能残留的旧 socket 文件 */
    if (bind(lfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        log_state("绑定控制 socket 失败: %s", strerror(errno));
        close(lfd);
        return -1;
    }
    if (listen(lfd, 1) < 0) {
        log_state("监听控制 socket 失败: %s", strerror(errno));
        close(lfd);
        unlink(sock_path);
        return -1;
    }
    log_state("控制 socket 就绪: %s（等待 Java 侧连接…）", sock_path);

    struct pollfd pfd = { .fd = lfd, .events = POLLIN };
    int pr = poll(&pfd, 1, ACCEPT_TIMEOUT_MS);
    if (pr <= 0) {
        log_state(pr == 0 ? "等待控制连接超时（%d ms）" : "poll 失败: %s",
                ACCEPT_TIMEOUT_MS, strerror(errno));
        close(lfd);
        unlink(sock_path);
        return -1;
    }
    int cfd = accept4(lfd, NULL, NULL, SOCK_CLOEXEC);
    close(lfd);
    if (cfd < 0) {
        log_state("接受控制连接失败: %s", strerror(errno));
        unlink(sock_path);
        return -1;
    }

    char cbuf[CMSG_SPACE(sizeof(int))];
    memset(cbuf, 0, sizeof(cbuf));
    struct iovec iov = { .iov_base = ctrl, .iov_len = sizeof(*ctrl) };
    struct msghdr mh;
    memset(&mh, 0, sizeof(mh));
    mh.msg_iov = &iov;
    mh.msg_iovlen = 1;
    mh.msg_control = cbuf;
    mh.msg_controllen = sizeof(cbuf);

    ssize_t n = recvmsg(cfd, &mh, 0);
    close(cfd);
    unlink(sock_path);
    if (n != (ssize_t)sizeof(*ctrl)) {
        log_state("控制消息长度异常: %zd（预期 %zu）", n, sizeof(*ctrl));
        return -1;
    }
    if (ctrl->magic != CTRL_MAGIC || ctrl->version != PROTO_VERSION) {
        log_state("控制消息协议不匹配（magic=0x%08x version=%u）",
                ctrl->magic, ctrl->version);
        return -1;
    }
    int fd = -1;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&mh); c != NULL; c = CMSG_NXTHDR(&mh, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
            int nfds = (int)((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
            if (nfds >= 1) {
                fd = ((const int *)CMSG_DATA(c))[0];
            }
            /* 多余的 fd 直接丢弃（内核已安装到本进程，关闭避免泄漏） */
            for (int i = 1; i < nfds; i++) {
                close(((const int *)CMSG_DATA(c))[i]);
            }
        }
    }
    if (fd < 0) {
        log_state("控制消息未携带 SCM_RIGHTS 文件描述符");
        return -1;
    }
    *pw_fd = fd;
    return 0;
}

// ------------------------------------------------------------------
// main
// ------------------------------------------------------------------

static void usage(FILE *out) {
    fputs(
        "用法: gudesk-portal-helper --fd-socket <path> [--node <id>] [--serial <n>]\n"
        "                          [--max-fps <n>]\n"
        "\n"
        "PipeWire 帧消费器：经控制 socket 接收 PipeWire 连接 fd（SCM_RIGHTS），\n"
        "绑定 portal 流节点，BGRA 帧以二进制协议写 stdout（协议见源码头部注释）。\n",
        out);
}

int main(int argc, char **argv) {
    const char *sock_path = NULL;
    uint32_t node_id = 0;
    uint64_t serial = 0;
    double max_fps = 0.0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--fd-socket") == 0 && i + 1 < argc) {
            sock_path = argv[++i];
        } else if (strcmp(argv[i], "--node") == 0 && i + 1 < argc) {
            node_id = (uint32_t)strtoul(argv[++i], NULL, 0);
        } else if (strcmp(argv[i], "--serial") == 0 && i + 1 < argc) {
            serial = strtoull(argv[++i], NULL, 0);
        } else if (strcmp(argv[i], "--max-fps") == 0 && i + 1 < argc) {
            max_fps = strtod(argv[++i], NULL);
        } else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            usage(stdout);
            return 0;
        } else {
            fprintf(stderr, "未知参数: %s\n", argv[i]);
            usage(stderr);
            return 1;
        }
    }
    if (sock_path == NULL) {
        fprintf(stderr, "缺少 --fd-socket 参数\n");
        usage(stderr);
        return 1;
    }

    signal(SIGPIPE, SIG_IGN);

    struct gd_ctrl ctrl;
    memset(&ctrl, 0, sizeof(ctrl));
    int pw_fd = -1;
    if (ctrl_receive(sock_path, &ctrl, &pw_fd) != 0) {
        return 2;
    }
    /* 控制消息可补充/覆盖命令行参数（serial 优先） */
    if (ctrl.node_id != 0) {
        node_id = ctrl.node_id;
    }
    if (ctrl.serial != 0) {
        serial = ctrl.serial;
    }
    if (node_id == 0 && serial == 0) {
        log_state("缺少流目标（--node/--serial 或控制消息均未提供）");
        close(pw_fd);
        return 2;
    }
    log_state("已接收 PipeWire fd（node=%" PRIu32 " serial=%" PRIu64 "）",
            node_id, serial);

    struct gd_state s;
    memset(&s, 0, sizeof(s));
    s.node_id = node_id;
    s.serial = serial;
    if (max_fps > 0.0) {
        s.min_interval_ns = (int64_t)(1000000000.0 / max_fps);
    }

    pw_init(NULL, NULL);
    s.loop = pw_main_loop_new(NULL);
    if (s.loop == NULL) {
        log_state("创建主循环失败");
        return 3;
    }
    pw_loop_add_signal(pw_main_loop_get_loop(s.loop), SIGINT, on_signal, &s);
    pw_loop_add_signal(pw_main_loop_get_loop(s.loop), SIGTERM, on_signal, &s);

    s.ctx = pw_context_new(pw_main_loop_get_loop(s.loop), NULL, 0);
    if (s.ctx == NULL) {
        log_state("创建 pw_context 失败");
        return 3;
    }
    /* fd 所有权移交 context（由其负责关闭） */
    s.core = pw_context_connect_fd(s.ctx, pw_fd, NULL, 0);
    if (s.core == NULL) {
        log_state("连接 PipeWire 失败（fd 无效或服务不可用）");
        close(pw_fd);
        return 3;
    }

    struct pw_properties *props = pw_properties_new(
            PW_KEY_MEDIA_TYPE, "Video",
            PW_KEY_MEDIA_CATEGORY, "Capture",
            NULL);
    if (props == NULL) {
        log_state("创建流属性失败");
        return 3;
    }
    if (serial != 0) {
        /* 优先用 object.serial 定位（node id 可能被复用） */
        pw_properties_setf(props, PW_KEY_TARGET_OBJECT, "%" PRIu64, serial);
    } else {
        /* PW_KEY_NODE_TARGET 在新头文件已标记弃用，node id 回退路径用字面量键 */
        pw_properties_setf(props, "node.target", "%" PRIu32, node_id);
    }

    s.stream = pw_stream_new(s.core, "gudesk-portal-helper", props);
    if (s.stream == NULL) {
        log_state("创建流失败");
        return 3;
    }
    struct spa_hook stream_listener;
    pw_stream_add_listener(s.stream, &stream_listener, &STREAM_EVENTS, &s);

    enum pw_stream_flags flags = PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS;
    if (pw_stream_connect(s.stream, PW_DIRECTION_INPUT, PW_ID_ANY, flags, NULL, 0) < 0) {
        log_state("流连接失败");
        return 3;
    }

    pw_main_loop_run(s.loop);

    log_state("退出: 共输出 %" PRIu64 " 帧", s.frames);
    if (s.map_cache.ptr != NULL) {
        munmap(s.map_cache.ptr, s.map_cache.len);
        s.map_cache.ptr = NULL;
    }
    pw_stream_destroy(s.stream);
    pw_context_destroy(s.ctx);
    pw_main_loop_destroy(s.loop);
    pw_deinit();
    free(s.staging);
    return 0;
}
