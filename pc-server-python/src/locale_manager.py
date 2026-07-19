# pc-server-python/src/locale_manager.py
import sys
import locale

# 語系對照表
TRANSLATIONS = {
    "en": {
        "pc_name": "PC Name: {}",
        "trusted_devices": "Trusted Devices",
        "no_trusted_devices": "No trusted devices",
        "unpair_device": "Unpair \"{}\"",
        "clear_all": "Clear all trusted devices",
        "startup_enable": "Run at startup",
        "exit_program": "Exit",
        "pair_request_title": "Mumiopad Pairing Request",
        "pair_request_msg": "Device \"{}\" is requesting to pair and control this PC.\n\nAllow pairing?"
    },
    "zh_TW": {
        "pc_name": "電腦名稱: {}",
        "trusted_devices": "已信任裝置",
        "no_trusted_devices": "無信任裝置",
        "unpair_device": "解除「{}」",
        "clear_all": "清除所有信任裝置",
        "startup_enable": "開機自動啟動",
        "exit_program": "結束程式",
        "pair_request_title": "Mumiopad 配對請求",
        "pair_request_msg": "裝置「{}」請求配對並控制此電腦。\n\n是否允許配對？"
    }
}

def detect_language() -> str:
    """
    偵測系統預設語言。
    只有繁體中文（台灣、香港、澳門、或 Hant 字符集）才判定為 'zh_TW'。
    其餘語系一律返回預設值 'en'。
    """
    try:
        if sys.platform == "win32":
            import ctypes
            windll = ctypes.windll.kernel32
            # 獲取 Windows 使用者介面語言代碼 (LCID)
            lcid = windll.GetUserDefaultUILanguage()
            # 1028 = zh-TW, 3076 = zh-HK, 5124 = zh-MO
            if lcid in (1028, 3076, 5124):
                return "zh_TW"
        else:
            # 非 Windows 系統的通用判斷
            default_locale = locale.getdefaultlocale()[0]
            if default_locale:
                lang = default_locale.lower()
                # 判斷是否包含 tw, hk, mo 或 hant (繁體)
                if "zh" in lang and ("tw" in lang or "hk" in lang or "mo" in lang or "hant" in lang):
                    return "zh_TW"
    except Exception:
        pass
    return "en"

_ACTIVE_LANG = detect_language()

def _(key: str) -> str:
    """
    獲取翻譯字串，若找不到則退回英文。
    """
    lang_dict = TRANSLATIONS.get(_ACTIVE_LANG, TRANSLATIONS["en"])
    return lang_dict.get(key, TRANSLATIONS["en"].get(key, key))