//! The bionic linker config the guest's libhybris reads
//! (`me.phie.tawc.install.LinkerConfig`).
//!
//! Android's `/linkerconfig` used to be bind-mounted into every rootfs.
//! Its SELinux label (`linkerconfig_file`) grants app domains `dir
//! search` but not `dir getattr`, so any `ls` that stats the entries of
//! `/` failed on that one entry. We copy the single file libhybris
//! wants into the rootfs instead, so `/linkerconfig` is gone from the
//! guest entirely.
//!
//! The first test deliberately runs the *interactive shell* shape of
//! `ls`: colour plus a `LS_COLORS` that defines an orphan class forces
//! coreutils to stat every entry of `/`, which the plain
//! non-interactive `ls /` the rest of the suite uses never does — that
//! blind spot is why the bind survived as long as it did.

use tawc_integration::adb;

/// `ls -l /` with colour classification active — a stat of every entry
/// of `/` — succeeds and reports no `/linkerconfig`.
#[test]
fn test_ls_root_stats_every_entry() {
    tawc_integration::helpers::test_init();
    let cmd = "LS_COLORS='or=40;31;01' ls --color=always -l /";
    let out = adb::rootfs_run(cmd).expect("rootfs ls -l /");
    let stdout = String::from_utf8_lossy(&out.stdout);
    let stderr = String::from_utf8_lossy(&out.stderr);
    assert!(
        out.status.success() && stderr.trim().is_empty(),
        "`{cmd}` failed (exit {:?})\nstdout:\n{stdout}\nstderr:\n{stderr}",
        out.status.code(),
    );
    assert!(
        !stdout.contains("linkerconfig"),
        "/linkerconfig is still present in the guest:\n{stdout}",
    );
}

/// The copy lands at the path the libhybris fork's
/// `kLdGeneratedConfigFilePath` points at, with the host file's
/// contents. Skipped on a host without `/linkerconfig` (pre-Android
/// 11), where there is nothing to copy.
#[test]
fn test_ld_config_copied_into_rootfs() {
    tawc_integration::helpers::test_init();
    let host_size = {
        let out = adb::shell("stat -c %s /linkerconfig/ld.config.txt 2>/dev/null || true")
            .expect("adb stat /linkerconfig/ld.config.txt");
        String::from_utf8_lossy(&out.stdout).trim().to_string()
    };
    if host_size.is_empty() {
        eprintln!("host has no /linkerconfig/ld.config.txt; nothing to copy");
        return;
    }

    let guest = "/usr/lib/hybris-config/ld.config.txt";
    let out = adb::rootfs_run(&format!("stat -c %s {guest}")).expect("rootfs stat ld.config.txt");
    let stdout = String::from_utf8_lossy(&out.stdout);
    assert!(
        out.status.success(),
        "{guest} missing in the rootfs (exit {:?})\nstderr:\n{}",
        out.status.code(),
        String::from_utf8_lossy(&out.stderr),
    );
    assert_eq!(
        stdout.trim(),
        host_size,
        "{guest} does not match the host file's size",
    );
}
