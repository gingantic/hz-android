use adblock::engine::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jlong, jstring};
use jni::JNIEnv;
use std::panic::catch_unwind;
use std::ptr;
use std::sync::Arc;

pub struct WrappedEngine {
    engine: Engine,
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
        Arc::into_raw(wrapped) as jlong
    });

    result.unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_rhnxdev_hzplayer_browser_adblock_AdBlockNative_nativeShouldBlock<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    engine_ptr: jlong,
    request_url: JString<'local>,
    page_url: JString<'local>,
    resource_type: JString<'local>,
) -> jboolean {
    if engine_ptr == 0 {
        return 0;
    }

    let result = catch_unwind(move || {
        let arc_ptr = engine_ptr as *const WrappedEngine;
        if arc_ptr.is_null() {
            return 0;
        }

        let wrapped = &*arc_ptr;

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
    engine_ptr: jlong,
    page_url: JString<'local>,
) -> jstring {
    if engine_ptr == 0 {
        return ptr::null_mut();
    }

    let result = catch_unwind(move || {
        let arc_ptr = engine_ptr as *const WrappedEngine;
        if arc_ptr.is_null() {
            return ptr::null_mut();
        }

        let wrapped = &*arc_ptr;
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

#[no_mangle]
pub unsafe extern "C" fn Java_com_rhnxdev_hzplayer_browser_adblock_AdBlockNative_nativeDestroyEngine(
    _env: JNIEnv,
    _class: JClass,
    engine_ptr: jlong,
) {
    if engine_ptr != 0 {
        let _ = catch_unwind(move || {
            let _ = Arc::from_raw(engine_ptr as *const WrappedEngine);
        });
    }
}
