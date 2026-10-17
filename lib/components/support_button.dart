import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:revoltvpn/logic/ad_manager.dart';
import 'package:revoltvpn/logic/session_timer.dart';
import 'package:revoltvpn/logic/app_colors.dart';

class SupportButton extends StatefulWidget {
  const SupportButton({super.key});

  @override
  State<SupportButton> createState() => _SupportButtonState();
}

class _SupportButtonState extends State<SupportButton> {
  bool _busy = false;

  Future<void> _handleTap() async {
    if (_busy) return;

    final ad = context.read<AdManager>();
    final timer = context.read<SessionTimer>();

    // Only available when there's an active session, once the persisted
    // support state is known, and only once per client-side VPN session.
    if ((!timer.isRunning && !timer.hasSyncedOnce) ||
        !timer.supportRewardStateLoaded ||
        timer.supportRewardClaimed) {
      return;
    }

    setState(() => _busy = true);

    try {
      final rewarded = await ad.showAd('support') != null;
      if (rewarded) {
        await timer.markSupportRewardClaimed();
        await timer.syncNow();
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<SessionTimer>(
      builder: (context, timer, _) {
        if (!timer.isRunning && !timer.hasSyncedOnce) {
          return const SizedBox.shrink();
        }

        final stateLoaded = timer.supportRewardStateLoaded;
        final claimed = timer.supportRewardClaimed;
        final disabled = !stateLoaded || claimed;

        return GestureDetector(
          onTap: disabled ? null : _handleTap,
          child: Opacity(
            opacity: disabled ? 0.55 : 1.0,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
              decoration: BoxDecoration(
                color: AppColors.glassBg,
                borderRadius: BorderRadius.circular(24),
                border: Border.all(
                  color: AppColors.accent.withAlpha(100),
                  width: 1,
                ),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    claimed ? Icons.check_circle_outline : Icons.favorite_border,
                    color: AppColors.accent,
                    size: 20,
                  ),
                  const SizedBox(width: 8),
                  _busy
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: AppColors.accent,
                          ),
                        )
                      : Text(
                          !stateLoaded
                              ? 'Checking support bonus…'
                              : claimed
                                  ? 'Support bonus used'
                                  : 'Support us — 30 min!',
                          style: const TextStyle(
                            color: AppColors.textWhite,
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
