use std::{env, path::PathBuf, process::Command};
fn output(command: &mut Command) -> String {
    let result = command.output().expect("run C toolchain");
    assert!(result.status.success(), "C toolchain: {}", String::from_utf8_lossy(&result.stderr));
    String::from_utf8(result.stdout).unwrap().trim().into()
}
fn main() {
    println!("cargo:rerun-if-changed=vendor/quickjs");
    println!("cargo:rerun-if-changed=src/quickjs-shim.c");
    println!("cargo:rerun-if-env-changed=CC");
    println!("cargo:rerun-if-env-changed=AR");
    let target = env::var("TARGET").unwrap();
    let out = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    let apple = target.contains("apple");
    let sdk = if target.contains("ios") {
        if target.contains("sim") || target.starts_with("x86_64") { "iphonesimulator" } else { "iphoneos" }
    } else { "macosx" };
    let compiler = if apple { output(Command::new("xcrun").args(["--sdk",sdk,"--find","clang"])) }
        else { env::var("CC").unwrap_or("cc".into()) };
    let ar = if apple { output(Command::new("xcrun").args(["--find","ar"])) }
        else { env::var("AR").unwrap_or("ar".into()) };
    let mut platform = vec![];
    if apple {
        let arch = if target.starts_with("aarch64") {"arm64"} else {"x86_64"};
        let triple = if sdk == "iphonesimulator" {format!("{arch}-apple-ios17.0-simulator")}
            else if sdk == "iphoneos" {format!("{arch}-apple-ios17.0")}
            else {format!("{arch}-apple-macos14.0")};
        platform.extend(["-target".to_string(),triple,"-isysroot".into(), output(Command::new("xcrun").args(["--sdk",sdk,"--show-sdk-path"]))]);
    }
    let sources = ["vendor/quickjs/quickjs.c","vendor/quickjs/dtoa.c","vendor/quickjs/libregexp.c","vendor/quickjs/libunicode.c","vendor/quickjs/cutils.c","src/quickjs-shim.c"];
    let mut objects = vec![];
    for (index, source) in sources.iter().enumerate() {
        let object = out.join(format!("quickjs-{index}.o"));
        let result = Command::new(&compiler).args(&platform)
            .args(["-std=gnu11","-O2","-fPIC","-fvisibility=hidden","-D_GNU_SOURCE","-DCONFIG_VERSION=\"2026-06-04\"","-Ivendor/quickjs","-c",source,"-o"]).arg(&object)
            .status().expect("compile bundled QuickJS");
        assert!(result.success(), "failed to compile {source}");
        objects.push(object);
    }
    let archive = out.join("libyoungmin_quickjs.a");
    let result = Command::new(ar).arg("crs").arg(&archive).args(objects).status().expect("archive QuickJS");
    assert!(result.success());
    println!("cargo:rustc-link-search=native={}",out.display());
    println!("cargo:rustc-link-lib=static=youngmin_quickjs");
    println!("cargo:rustc-link-lib=m");
}
