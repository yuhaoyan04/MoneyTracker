# Payment guard: device investigation

2026-11-05; Huawei NCO-AL00, HarmonyOS 4.2 (API 31), MoneyTracker 2.42.0.
No payment was made. Evidence below describes one observed transaction flow, not all devices or app versions.

* Guard and accessibility services were running, and usage and overlay access were granted.
* During a Taobao-to-WeChat payment attempt, diagnostics recorded Taobao checkout and WeChat payment window events, but zero readable text nodes. The guard therefore did not obtain a qualified amount or request an overlay.
* A separate UI Automator inspection of the Taobao order confirmation page returned 14 container nodes, no text or content descriptions. The WeChat WxaLiteAppTransparentLiteUI returned a single empty FrameLayout. These observations do not establish why the content was inaccessible.
* The WeChat window's reported attributes included SECURE and HIDE_NON_SYSTEM_OVERLAY_WINDOWS. The existing guard uses TYPE_APPLICATION_OVERLAY; attachment alone is not evidence it can be seen or intercept touches on this window.
* The Taobao order confirmation window did not report either flag. With the user's permission an in-memory ADB screenshot produced a nonuniform image. A separate, in-memory Windows Chinese OCR experiment identified fixed labels for a payable amount and the user-reported button “继续付款”, plus a currency-number pattern. It did not establish the amount's correctness, bind it to the label, or test Android's accessibility screenshot API, the on-device model, low-value cases, or other screens. No screenshot or recognized content was saved or displayed.
* The checkout action list did not include “继续付款”. Adding it covers accessible text with that label but does not make this particular inaccessible page work.

Candidate work: explicitly opt-in on-device OCR on supported checkout pages, validate that the Android screenshot API works, associate a unique final payable value with its label, reject uncertain recognition and stale pages, and test amount thresholds, false positives, backgrounding, and privacy on real devices. A connected accessibility service's TYPE_ACCESSIBILITY_OVERLAY is a separate display experiment and must not be assumed to be visible or touchable over a protected payment window. Unknown amounts must not be treated as above threshold.
