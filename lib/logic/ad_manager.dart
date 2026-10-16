import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:google_mobile_ads/google_mobile_ads.dart';
import 'package:revoltvpn/logic/crypto_service.dart';
import 'package:revoltvpn/logic/hivemind_service.dart';
import 'package:revoltvpn/logic/app_config.dart';
import 'package:revoltvpn/logic/consent_manager.dart';

class AdManager extends ChangeNotifier {
  static const bool adsEnabled = false;

  RewardedAd? _rewardedAd;

  bool _isAdLoaded = false;
  bool get isAdLoaded => adsEnabled ? _isAdLoaded : true;

  bool _isAdLoading = false;
  bool get isAdLoading => adsEnabled ? _isAdLoading : false;

  Completer<bool>? _loadCompleter;

  // Google-provided test ad unit
  static String get _adUnitId => AppConfig.adUnitId;

  AdManager() {
    if (adsEnabled) preloadAd();
  }

  static Future<void>? _sdkInit;
  static Future<void> ensureSdkInitialized() {
    if (!adsEnabled) return Future.value();
    return _sdkInit ??= _initSdk();
  }

  static Future<void> _initSdk() async {
    try {
      await ConsentManager.requestConsentIfNeeded()
          .timeout(const Duration(seconds: 5));
    } catch (e) {
      debugPrint('[AdManager] Consent init skipped: $e');
    }
    try {
      await MobileAds.instance.initialize().timeout(const Duration(seconds: 5));
    } catch (e) {
      debugPrint('[AdManager] MobileAds init skipped: $e');
    }
  }

  Future<bool> preloadAd() async {
    if (!adsEnabled) return true;
    await ensureSdkInitialized();
    if (_isAdLoaded) return true;
    if (_isAdLoading) return _loadCompleter?.future ?? Future.value(false);

    _isAdLoading = true;
    _loadCompleter = Completer<bool>();
    notifyListeners();

    if (kIsWeb) {
      await Future.delayed(const Duration(milliseconds: 500));
      _isAdLoaded = true;
      _isAdLoading = false;
      notifyListeners();
      _loadCompleter?.complete(true);
      return true;
    }

    RewardedAd.load(
      adUnitId: _adUnitId,
      request: const AdRequest(),
      rewardedAdLoadCallback: RewardedAdLoadCallback(
        onAdLoaded: (ad) {
          _rewardedAd = ad;
          _isAdLoaded = true;
          _isAdLoading = false;
          notifyListeners();
          if (!(_loadCompleter?.isCompleted ?? true)) {
            _loadCompleter?.complete(true);
          }
        },
        onAdFailedToLoad: (error) {
          debugPrint('Rewarded ad failed to load: ${error.message}');
          _isAdLoaded = false;
          _isAdLoading = false;
          notifyListeners();
          if (!(_loadCompleter?.isCompleted ?? true)) {
            _loadCompleter?.complete(false);
          }
        },
      ),
    );

    return _loadCompleter!.future;
  }

  // ── Show ad (or debug bypass) ─────────────────────────────────────

  // Returns the nonce put in the SSV custom_data, null if unrewarded. connect()
  // must reuse it: minting a second nonce there rejects the ad's own session.
  Future<String?> showAd(String adType) async {
    if (!adsEnabled) {
      // Main connect fires its own bypass in fetchConfigDirectly (rule 87).
      if (adType != 'support' || !kDebugMode) return null;

      // Needs ADMOB_BYPASS=True server-side.
      final deviceId = await CryptoService.getDeviceId();
      final nonce = HivemindService.newNonce();
      try {
        final customData = jsonEncode({
          'device_id': deviceId,
          'ad_type': adType,
          'nonce': nonce,
        });
        final fakeUrl = Uri.parse(
            '${AppConfig.hivemindApiPublic}/admob/callback'
            '?signature=test&key_id=test'
            '&custom_data=${Uri.encodeComponent(customData)}');
        await HivemindService.directGet(fakeUrl, timeout: const Duration(seconds: 8));
        return nonce;
      } catch (_) {
        return null;
      }
    }

    await ensureSdkInitialized();

    if (!_isAdLoaded || _rewardedAd == null) {
      final loaded = await preloadAd();
      if (!loaded || _rewardedAd == null) {
        debugPrint('[AdManager] Cannot show ad, failed to load.');
        return null;
      }
    }

    final deviceId = await CryptoService.getDeviceId();
    final nonce = HivemindService.newNonce();

    final ssvOptions = ServerSideVerificationOptions(
      customData: jsonEncode({
        'device_id': deviceId,
        'ad_type': adType,
        'nonce': nonce,
      }),
    );

    Completer<bool> rewardCompleter = Completer<bool>();

    _rewardedAd!.fullScreenContentCallback = FullScreenContentCallback(
      onAdShowedFullScreenContent: (ad) => debugPrint('[AdManager] Ad showing.'),
      onAdDismissedFullScreenContent: (ad) {
        debugPrint('[AdManager] Ad dismissed.');
        ad.dispose();
        _isAdLoaded = false;
        _rewardedAd = null;
        preloadAd();
        if (!rewardCompleter.isCompleted) {
          rewardCompleter.complete(false);
        }
      },
      onAdFailedToShowFullScreenContent: (ad, error) {
        debugPrint('[AdManager] Ad failed to show: $error');
        ad.dispose();
        _isAdLoaded = false;
        _rewardedAd = null;
        if (!rewardCompleter.isCompleted) {
          rewardCompleter.complete(false);
        }
      },
    );

    _rewardedAd!.setServerSideOptions(ssvOptions);
    await _rewardedAd!.show(
      onUserEarnedReward: (AdWithoutView ad, RewardItem reward) {
        debugPrint('[AdManager] Reward earned: ${reward.amount} ${reward.type}');
        if (!rewardCompleter.isCompleted) {
          rewardCompleter.complete(true);
        }
      },
    );

    return await rewardCompleter.future ? nonce : null;
  }

  @override
  void dispose() {
    _rewardedAd?.dispose();
    super.dispose();
  }
}
