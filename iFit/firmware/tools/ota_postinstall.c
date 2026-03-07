/*
 * ota_postinstall - Post-install setup for rooted OTA.
 * Runs in Android recovery (minimal environment: no shell, no toybox).
 * Called from updater-script via run_program().
 *
 * Does:
 *   1. Mounts /data
 *   2. Creates /data/media/0/.wolfDev (safety net)
 *   3. Creates /data/misc/adb/ and installs ADB authorized key
 *   4. Conditional packages.xml wipe (first install only, via marker file)
 *   5. Unmounts /data
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/mount.h>

#define DATA_MOUNT "/data"
#define WOLFDEV_DIR "/data/media/0"
#define WOLFDEV_PATH "/data/media/0/.wolfDev"
#define ADB_DIR "/data/misc/adb"
#define ADB_KEYS_SRC "/tmp/adb_keys"
#define ADB_KEYS_DST "/data/misc/adb/adb_keys"
#define MARKER_PATH "/data/system/.hyperborea"
#define FIXUP_PATH "/data/system/.hyperborea_fixup"
#define PACKAGES_XML "/data/system/packages.xml"
#define PACKAGES_LIST "/data/system/packages.list"

/* media_rw uid/gid = 1023 */
#define MEDIA_RW_UID 1023
#define MEDIA_RW_GID 1023

/* system uid = 1000 */
#define SYSTEM_UID 1000

/* shell gid = 2000 */
#define SHELL_GID 2000

static int mkdirs(const char *path, mode_t mode) {
    char tmp[256];
    char *p;
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = 0;
            mkdir(tmp, mode);
            *p = '/';
        }
    }
    return mkdir(tmp, mode);
}

static int copy_file(const char *src, const char *dst, uid_t uid, gid_t gid, mode_t mode) {
    char buf[4096];
    int src_fd = open(src, O_RDONLY);
    if (src_fd < 0) return -1;
    int dst_fd = open(dst, O_WRONLY | O_CREAT | O_TRUNC, mode);
    if (dst_fd < 0) { close(src_fd); return -1; }
    int n;
    while ((n = read(src_fd, buf, sizeof(buf))) > 0) {
        if (write(dst_fd, buf, n) != n) {
            close(src_fd);
            close(dst_fd);
            return -1;
        }
    }
    close(src_fd);
    close(dst_fd);
    if (n < 0) return -1;
    chown(dst, uid, gid);
    chmod(dst, mode);
    return 0;
}

int main(int argc, char *argv[]) {
    const char *data_dev = "/dev/block/mmcblk0p10";
    if (argc > 1) data_dev = argv[1];

    printf("ota_postinstall: starting\n");

    /* 1. Mount /data */
    mkdir(DATA_MOUNT, 0771);
    if (mount(data_dev, DATA_MOUNT, "ext4", 0, "") < 0) {
        /* Might already be mounted */
        if (errno != EBUSY) {
            printf("ota_postinstall: mount %s failed: %s\n", data_dev, strerror(errno));
            return 1;
        }
        printf("ota_postinstall: /data already mounted\n");
    } else {
        printf("ota_postinstall: mounted %s on /data\n", data_dev);
    }

    /* 2. Create .wolfDev */
    mkdirs(WOLFDEV_DIR, 0770);
    int fd = open(WOLFDEV_PATH, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        close(fd);
        chown(WOLFDEV_PATH, MEDIA_RW_UID, MEDIA_RW_GID);
        chown(WOLFDEV_DIR, MEDIA_RW_UID, MEDIA_RW_GID);
        printf("ota_postinstall: created %s\n", WOLFDEV_PATH);
    } else {
        printf("ota_postinstall: failed to create %s: %s\n", WOLFDEV_PATH, strerror(errno));
    }

    /* 3. Create ADB keys directory and install authorized key */
    mkdirs(ADB_DIR, 0770);
    chown(ADB_DIR, SYSTEM_UID, SHELL_GID);
    printf("ota_postinstall: created %s\n", ADB_DIR);

    if (copy_file(ADB_KEYS_SRC, ADB_KEYS_DST, SYSTEM_UID, SHELL_GID, 0640) == 0) {
        printf("ota_postinstall: installed ADB key to %s\n", ADB_KEYS_DST);
    } else {
        printf("ota_postinstall: failed to install ADB key: %s\n", strerror(errno));
    }

    /* 4. Conditional packages.xml wipe (first install only) */
    struct stat st;
    if (stat(MARKER_PATH, &st) == 0) {
        printf("ota_postinstall: marker exists, skipping packages.xml wipe\n");
    } else {
        unlink(PACKAGES_XML);
        unlink(PACKAGES_LIST);
        printf("ota_postinstall: wiped packages.xml and packages.list (first install)\n");

        /* Create marker for subsequent installs */
        mkdirs("/data/system", 0775);
        fd = open(MARKER_PATH, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) {
            close(fd);
            printf("ota_postinstall: created marker %s\n", MARKER_PATH);
        } else {
            printf("ota_postinstall: failed to create marker: %s\n", strerror(errno));
        }

        /* Signal install-recovery.sh to fix data dir ownership on first boot */
        fd = open(FIXUP_PATH, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) {
            close(fd);
            printf("ota_postinstall: created fixup marker %s\n", FIXUP_PATH);
        } else {
            printf("ota_postinstall: failed to create fixup marker: %s\n", strerror(errno));
        }
    }

    /* 5. Unmount /data */
    sync();
    if (umount(DATA_MOUNT) < 0) {
        printf("ota_postinstall: unmount /data failed: %s (non-fatal)\n", strerror(errno));
    } else {
        printf("ota_postinstall: unmounted /data\n");
    }

    printf("ota_postinstall: done\n");
    return 0;
}
