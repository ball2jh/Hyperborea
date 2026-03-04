/*
 * ota_postinstall - Post-install setup for Hyperborea OTA.
 * Runs in Android recovery (minimal environment: no shell, no toybox).
 * Called from updater-script via run_program().
 *
 * With ERU removed from the OTA, this only needs to:
 *   1. Mount /data
 *   2. Create .wolfDev (safety net against leftover iFit code)
 *   3. Create ADB keys directory
 *   4. Create immutable /data/update.zip (blocks iFit OTA overwrites)
 *   5. Install safety-net IFW rules (GlassOS, Rivendell overlay)
 *   6. Unmount /data
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/mount.h>
#include <sys/ioctl.h>
#include <linux/fs.h>

#define DATA_MOUNT "/data"
#define WOLFDEV_DIR "/data/media/0"
#define WOLFDEV_PATH "/data/media/0/.wolfDev"
#define ADB_DIR "/data/misc/adb"
#define UPDATE_ZIP "/data/update.zip"
#define IFW_DIR "/data/system/ifw"
#define IFW_FILE "/data/system/ifw/ifit_firewall.xml"

#define MEDIA_RW_UID 1023
#define MEDIA_RW_GID 1023
#define SYSTEM_UID 1000
#define SYSTEM_GID 1000
#define SHELL_GID 2000

/* Safety-net IFW rules — blocks GlassOS auto-start and Rivendell overlays.
 * These are minimal rules kept as defense-in-depth in case iFit APKs
 * are somehow sideloaded after ERU has been removed from the firmware.
 */
static const char *IFW_CONTENT =
    "<rules>\n"
    "<!-- Safety net: block GlassOS auto-start (in case sideloaded) -->\n"
    "<broadcast block=\"true\" log=\"true\">\n"
    "  <component-filter name=\"com.ifit.glassos_service/com.ifit.glassos_service.GlassOSAutoStartReceiver\" />\n"
    "</broadcast>\n"
    "\n"
    "<!-- Safety net: block Rivendell overlay control -->\n"
    "<broadcast block=\"true\" log=\"true\">\n"
    "  <intent-filter>\n"
    "    <action name=\"com.ifit.rivendell.SYSTEM_OVERLAY_START\" />\n"
    "  </intent-filter>\n"
    "</broadcast>\n"
    "<broadcast block=\"true\" log=\"true\">\n"
    "  <intent-filter>\n"
    "    <action name=\"com.ifit.rivendell.SYSTEM_OVERLAY_STOP\" />\n"
    "  </intent-filter>\n"
    "</broadcast>\n"
    "\n"
    "</rules>\n";

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

static int set_immutable(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    int flags = 0;
    if (ioctl(fd, FS_IOC_GETFLAGS, &flags) < 0) { close(fd); return -1; }
    flags |= FS_IMMUTABLE_FL;
    int ret = ioctl(fd, FS_IOC_SETFLAGS, &flags);
    close(fd);
    return ret;
}

static int write_file(const char *path, const char *content, uid_t uid, gid_t gid, mode_t mode) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, mode);
    if (fd < 0) return -1;
    int len = strlen(content);
    int written = write(fd, content, len);
    close(fd);
    if (written != len) return -1;
    chown(path, uid, gid);
    chmod(path, mode);
    return 0;
}

int main(int argc, char *argv[]) {
    (void)argc;
    (void)argv;

    /* Step 1: Mount /data */
    printf("ota_postinstall: mounting /data...\n");
    mkdir(DATA_MOUNT, 0771);
    if (mount("/dev/block/mmcblk0p10", DATA_MOUNT, "ext4",
              MS_NOATIME | MS_NODEV | MS_NOSUID, "") != 0) {
        /* Already mounted is OK */
        if (errno != EBUSY) {
            printf("ota_postinstall: mount /data failed: %s\n", strerror(errno));
            return 1;
        }
        printf("ota_postinstall: /data already mounted\n");
    }

    /* Step 2: Create .wolfDev (prevents leftover iFit code from disabling ADB) */
    printf("ota_postinstall: creating .wolfDev...\n");
    mkdirs(WOLFDEV_DIR, 0770);
    chown(WOLFDEV_DIR, MEDIA_RW_UID, MEDIA_RW_GID);
    int fd = open(WOLFDEV_PATH, O_WRONLY | O_CREAT | O_TRUNC, 0660);
    if (fd >= 0) {
        close(fd);
        chown(WOLFDEV_PATH, MEDIA_RW_UID, MEDIA_RW_GID);
        chmod(WOLFDEV_PATH, 0660);
        printf("ota_postinstall: .wolfDev created\n");
    } else {
        printf("ota_postinstall: .wolfDev create failed: %s\n", strerror(errno));
    }

    /* Step 3: Create ADB keys directory */
    printf("ota_postinstall: creating ADB directory...\n");
    mkdirs(ADB_DIR, 0750);
    chown(ADB_DIR, SYSTEM_UID, SHELL_GID);
    chmod(ADB_DIR, 0750);

    /* Step 4: Create immutable empty /data/update.zip (blocks iFit OTA overwrites) */
    printf("ota_postinstall: creating immutable update.zip...\n");
    /* Clear immutable flag first in case it already exists */
    fd = open(UPDATE_ZIP, O_RDONLY);
    if (fd >= 0) {
        int flags = 0;
        ioctl(fd, FS_IOC_GETFLAGS, &flags);
        flags &= ~FS_IMMUTABLE_FL;
        ioctl(fd, FS_IOC_SETFLAGS, &flags);
        close(fd);
    }
    fd = open(UPDATE_ZIP, O_WRONLY | O_CREAT | O_TRUNC, 0000);
    if (fd >= 0) {
        close(fd);
        chown(UPDATE_ZIP, 0, 0);
        chmod(UPDATE_ZIP, 0000);
        if (set_immutable(UPDATE_ZIP) == 0) {
            printf("ota_postinstall: immutable update.zip created\n");
        } else {
            printf("ota_postinstall: set_immutable failed: %s\n", strerror(errno));
        }
    } else {
        printf("ota_postinstall: update.zip create failed: %s\n", strerror(errno));
    }

    /* Step 5: Install safety-net IFW rules */
    printf("ota_postinstall: installing IFW rules...\n");
    mkdirs(IFW_DIR, 0700);
    chown(IFW_DIR, SYSTEM_UID, SYSTEM_GID);
    chmod(IFW_DIR, 0700);
    if (write_file(IFW_FILE, IFW_CONTENT, SYSTEM_UID, SYSTEM_GID, 0600) == 0) {
        printf("ota_postinstall: IFW rules installed\n");
    } else {
        printf("ota_postinstall: IFW rules install failed: %s\n", strerror(errno));
    }

    /* Step 6: Sync and unmount /data */
    printf("ota_postinstall: syncing and unmounting /data...\n");
    sync();
    if (umount(DATA_MOUNT) != 0) {
        printf("ota_postinstall: umount /data failed: %s (non-fatal)\n", strerror(errno));
    }

    printf("ota_postinstall: done\n");
    return 0;
}
