use adblock::engine::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jlong, jstring};
use jni::JNIEnv;
use std::collections::HashMap;
use std::panic::catch_unwind;
use std::ptr;
use std::sync::{Arc, OnceLock, RwLock};

pub struct WrappedEngine {
    engine: Engine,
}

/// Live engines keyed by the address of their `Arc` allocation — the JNI handle
/// is only a key, never dereferenced. Lookups clone the `Arc`, so a reload can
/// unregister an engine while in-flight requests on other threads still hold it.
static ENGINES: OnceLock<RwLock<HashMap<usize, Arc<WrappedEngine>>>> = OnceLock::new();

fn engines() -> &'static RwLock<HashMap<usize, Arc<WrappedEngine>>> {
    ENGINES.get_or_init(|| RwLock::new(HashMap::new()))
}

/// Clones the engine registered under `handle`, or `None` if it is unknown
/// (never created, or already destroyed by a reload).
fn acquire(handle: jlong) -> Option<Arc<WrappedEngine>> {
    if handle == 0 {
        return None;
    }
    engines()
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .get(&(handle as usize))
        .cloned()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_rhnxdev_hzplayer_browser_adblock_AdBlockNative_nativeCreateEngine<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rules_array: JObjectArray<'local>,
) -> jlong {
    let result = catch_unwind(move || {
        let len = match env.get_array_length(&rules_array) {
            Ok(l) => l,
            Err(_) => return 0,
        };

        let mut filter_set = FilterSet::new(true);

        for i in 0..len {
            if let Ok(obj) = env.get_object_array_element(&rules_array, i) {
                let jstr: JString = obj.into();
                let str_val: String = env
                    .get_string(&jstr)
                    .map(|s| String::from(s.to_str().unwrap_or("")))
                    .unwrap_or_default();
                if !str_val.is_empty() {
                    filter_set.add_filter_list(str_val, ParseOptions::default());
                }
            }
        }

        let engine = Engine::new_with_filter_set(filter_set);
        let wrapped = Arc::new(WrappedEngine { engine });
        let handle = Arc::as_ptr(&wrapped) as usize as jlong;

        engines()
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .insert(handle as usize, wrapped);

        handle
    });

    result.unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_rhnxdev_hzplayer_browser_adblock_AdBlockNative_nativeShouldBlock<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    engine_handle: jlong,
    request_url: JString<'local>,
    page_url: JString<'local>,
    resource_type: JString<'local>,
) -> jboolean {
    let result = catch_unwind(move || {
        let Some(wrapped) = acquire(engine_handle) else {
            return 0;
        };

        let req_url_str = env
            .get_string(&request_url)
            .map(|s| String::from(s.to_str().unwrap_or("")))
            .unwrap_or_default();

        if req_url_str.is_empty() {
            return 0;
        }

        let page_url_str = env
            .get_string(&page_url)
            .map(|s| String::from(s.to_str().unwrap_or("")))
            .unwrap_or_default();
        let res_type_str = env
            .get_string(&resource_type)
            .map(|s| String::from(s.to_str().unwrap_or("")))
            .unwrap_or_default();

        let request = match Request::new(&req_url_str, &page_url_str, &res_type_str, "GET") {
            Ok(r) => r,
            Err(_) => return 0,
        };

        let check_res = wrapped.engine.check_network_request(&request);
        let is_blocked = check_res.filter.is_some() && check_res.exception.is_none();
        if is_blocked { 1 } else { 0 }
    });

    result.unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_rhnxdev_hzplayer_browser_adblock_AdBlockNative_nativeGetCosmeticCss<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    engine_handle: jlong,
    page_url: JString<'local>,
) -> jstring {
    let result = catch_unwind(move || {
        let Some(wrapped) = acquire(engine_handle) else {
            return ptr::null_mut();
        };

        let page_url_str = env
            .get_string(&page_url)
            .map(|s| String::from(s.to_str().unwrap_or("")))
            .unwrap_or_default();

        if page_url_str.is_empty() {
            return ptr::null_mut();
        }

        let resources = wrapped.engine.url_cosmetic_resources(&page_url_str);
        let css = resources.hide_selectors.into_iter().collect::<Vec<_>>().join(", ");

        let full_css = if css.is_empty() {
            String::new()
        } else {
            format!(
                "{} {{ display: none !important; visibility: hidden !important; height: 0 !important; max-height: 0 !important; opacity: 0 !important; pointer-events: none !important; }}",
                css
            )
        };

        match env.new_string(full_css) {
            Ok(js) => js.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    });

    result.unwrap_or(ptr::null_mut())
}

/// Unregisters the engine. Safe to call twice and safe while requests are in
/// flight — the engine is freed when the last request holding it finishes.
#[no_mangle]
pub unsafe extern "C" fn Java_com_rhnxdev_hzplayer_browser_adblock_AdBlockNative_nativeDestroyEngine(
    _env: JNIEnv,
    _class: JClass,
    engine_handle: jlong,
) {
    if engine_handle == 0 {
        return;
    }
    let _ = catch_unwind(move || {
        engines()
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&(engine_handle as usize));
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn register(engine: Arc<WrappedEngine>) -> jlong {
        let handle = Arc::as_ptr(&engine) as usize as jlong;
        engines()
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .insert(handle as usize, engine);
        handle
    }

    fn unregister(handle: jlong) -> Option<Arc<WrappedEngine>> {
        engines()
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&(handle as usize))
    }

    fn empty_engine() -> Arc<WrappedEngine> {
        Arc::new(WrappedEngine {
            engine: Engine::new_with_filter_set(FilterSet::new(false)),
        })
    }

    fn check(engine: &WrappedEngine) {
        let request =
            Request::new("https://example.com/ad.js", "https://example.com", "script", "GET")
                .expect("valid request");
        let _ = engine.engine.check_network_request(&request);
    }

    #[test]
    fn acquire_ignores_zero_and_unknown_handles() {
        assert!(acquire(0).is_none());
        assert!(acquire(-1).is_none());
    }

    #[test]
    fn destroy_unregisters_but_in_flight_clone_stays_usable() {
        let handle = register(empty_engine());

        // A request thread acquires the engine, then a reload unregisters it.
        let in_flight = acquire(handle).expect("registered engine must be acquired");
        assert!(unregister(handle).is_some());

        assert!(acquire(handle).is_none(), "handle must not resolve after destroy");
        // The clone still owns a live engine. With the old raw-pointer scheme
        // this use ran on freed memory.
        assert_eq!(Arc::strong_count(&in_flight), 1);
        check(&in_flight);
    }

    #[test]
    fn destroy_is_idempotent() {
        let handle = register(empty_engine());
        assert!(unregister(handle).is_some());
        assert!(unregister(handle).is_none());
    }

    #[test]
    fn concurrent_requests_survive_a_concurrent_destroy() {
        let handle = register(empty_engine());
        let workers: Vec<_> = (0..8)
            .map(|_| {
                std::thread::spawn(move || {
                    for _ in 0..200 {
                        if let Some(engine) = acquire(handle) {
                            check(&engine);
                        }
                    }
                })
            })
            .collect();

        unregister(handle);
        for worker in workers {
            worker.join().expect("worker must not panic");
        }
    }
}
