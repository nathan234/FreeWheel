#[cfg(feature = "ffi")]
fn main() {
    uniffi::uniffi_bindgen_main()
}

// Without the ffi feature this binary is a no-op; keeping it buildable in all
// configurations lets tools that build every target (e.g. cargo kani) work
// without feature flags.
#[cfg(not(feature = "ffi"))]
fn main() {
    eprintln!("uniffi-bindgen requires --features \"ffi uniffi/cli\"");
    std::process::exit(1);
}
