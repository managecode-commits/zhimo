fn main() {
    println!("cargo:rerun-if-changed=native/rime_shim.c");
    println!("cargo:rerun-if-env-changed=RIME_ROOT");
    if std::env::var_os("CARGO_FEATURE_NATIVE_LIBRIME").is_none() {
        return;
    }
    let root = std::env::var("RIME_ROOT").expect("native-librime requires RIME_ROOT");
    cc::Build::new()
        .file("native/rime_shim.c")
        .include(format!("{root}/include"))
        .warnings(true)
        .compile("shurufa_rime_shim");
    println!("cargo:rustc-link-search=native={root}/lib");
    println!("cargo:rustc-link-lib=rime");
}
