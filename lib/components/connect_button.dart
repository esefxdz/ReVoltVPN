import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:revoltvpn/logic/app_colors.dart';
import 'package:revoltvpn/logic/vpn_connection.dart';
import 'package:revoltvpn/logic/session_timer.dart';
import 'package:revoltvpn/logic/ad_manager.dart';
import 'package:revoltvpn/logic/haptic_settings.dart';

class ConnectButton extends StatefulWidget {
  const ConnectButton({super.key});

  @override
  State<ConnectButton> createState() => _ConnectButtonState();
}

class _ConnectButtonState extends State<ConnectButton>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  bool _busy = false;
  DateTime _lastTap = DateTime.fromMillisecondsSinceEpoch(0);
  AnimationController? _pulse;
  Animation<double>? _pulseAnim;
  VpnConnection? _vpn;

  double get _pulseValue => _pulseAnim?.value ?? 0.0;

  @override
  void initState() {
    super.initState();
    final pulse = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2000),
    );
    _pulse = pulse;
    _pulseAnim = Tween(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: pulse, curve: Curves.easeInOut),
    );
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _vpn != null) return;
      final vpn = context.read<VpnConnection>();
      _vpn = vpn;
      vpn.addListener(_onVpnChanged);
      _onVpnChanged();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _vpn?.removeListener(_onVpnChanged);
    _pulse?.dispose();
    super.dispose();
  }

  void _onVpnChanged() {
    final vpn = _vpn;
    if (vpn == null) return;
    if (vpn.status == VpnStatus.connected) {
      _pulse?.repeat(reverse: true);
    } else {
      _pulse?.stop();
      _pulse?.reset();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (!mounted) return;
    if (state != AppLifecycleState.resumed) {
      _pulse?.stop();
      return;
    }
    if (_vpn?.status == VpnStatus.connected) {
      _pulse?.repeat(reverse: true);
    }
  }

  Future<void> _handleTap() async {
    final now = DateTime.now();
    if (now.difference(_lastTap).inMilliseconds < 1000) return;
    _lastTap = now;

    final vpn = context.read<VpnConnection>();
    final timer = context.read<SessionTimer>();
    final ad = context.read<AdManager>();

    if (vpn.status == VpnStatus.connected || vpn.status == VpnStatus.connecting) {
      _busy = false;
      await timer.disconnect();
      HapticSettings.success();
      return;
    }

    if (vpn.status == VpnStatus.disconnecting || _busy) return;

    setState(() => _busy = true);
    try {
      final adNonce = await ad.showAd('main');
      if (adNonce == null && AdManager.adsEnabled) return;

      final ok = await vpn.connect(adNonce: adNonce);
      if (ok) {
        await timer.start();
        HapticSettings.success();
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<VpnConnection>(
      builder: (context, vpn, _) {
        final isConnected = vpn.status == VpnStatus.connected;
        final spinning = vpn.status == VpnStatus.connecting ||
            vpn.status == VpnStatus.disconnecting;

        return GestureDetector(
          onTap: _handleTap,
          child: _pulseAnim != null
              ? AnimatedBuilder(
                  animation: _pulseAnim!,
                  child: _buildInner(spinning),
                  builder: (_, child) =>
                      _buildCircle(isConnected, _pulseValue, child),
                )
              : _buildCircle(isConnected, 0, _buildInner(spinning)),
        );
      },
    );
  }

  Widget _buildInner(bool spinning) {
    if (spinning) {
      return const Center(
        child: SizedBox(
          width: 36,
          height: 36,
          child: CircularProgressIndicator(
            strokeWidth: 2.5,
            color: AppColors.accent70,
          ),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.all(14),
      child: Image.asset('assets/brand.png', fit: BoxFit.contain),
    );
  }

  Widget _buildCircle(bool isConnected, double pulse, Widget? child) {
    final glowAlpha = isConnected ? ((0.3 + pulse * 0.25) * 255).round() : 0;
    final glowRadius = isConnected ? 18.0 + (pulse * 14) : 0.0;

    return Container(
      width: 260,
      height: 260,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: AppColors.bgDark,
        border: Border.all(
          color: isConnected ? AppColors.accent : AppColors.slate50,
          width: isConnected ? 2.5 : 1.5,
        ),
        boxShadow: isConnected
            ? [
                BoxShadow(
                  color: Color.fromARGB(glowAlpha, 255, 214, 0),
                  blurRadius: glowRadius,
                  spreadRadius: glowRadius * 0.3,
                ),
              ]
            : [],
      ),
      clipBehavior: Clip.hardEdge,
      child: child,
    );
  }
}
