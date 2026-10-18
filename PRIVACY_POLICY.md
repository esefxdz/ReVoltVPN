# Privacy Policy

**Last updated:** October 2026

ReVoltVPN is a free VPN service built and operated by ReVolt Team.

## In-app disclosure

When you first open the app, we show a brief summary of this policy before you connect. You can review this full policy at any time from the sidebar menu.

## What we collect

We collect the minimum required to operate the service:

- A randomly generated device ID created on your phone. It is stored on your device and reused between sessions, and it is not linked to your identity in any way.
- Your connection duration and data usage for the current session, linked to your device ID. This is used solely to enforce the per-session quota (see below).

## What our server sees

To start, check, or refresh your session, the app contacts our API directly rather than through the VPN tunnel. Our server therefore sees the IP address you are connecting from, alongside your device ID, for as long as the app is running. This is a necessary consequence of how sessions are issued and kept alive — it is separate from your browsing traffic, which is carried inside the tunnel and is described below.

## Session quotas

Each session lasts up to **2 hours** or **10 GB** of data — whichever comes first. When your session expires or hits the data cap, the tunnel disconnects. You can start a new session by watching another ad. Session records (duration and bytes transferred) are held on our server for the life of your session and removed when it ends.

Support ads (the "Support us" button) extend an active session by 30 minutes and add extra data allowance.

## Operational logs

Server logs are written to the system journal. These logs include truncated device IDs (first 8 characters only), session start/stop events, and error messages. Logs stay on the server and are accessible only to the server operator via local console; retention follows the server's journal settings. We also maintain an aggregate total of all data ever transferred (in GB) for capacity planning — this counter is not tied to any individual device.

## What we do not collect

- We do not collect your name, email, or any personal information.
- We do not log your internet traffic, browsing history, DNS queries, or destination IPs.
- We do not inspect or store the content of your traffic.
- We do not sell your data. We do not share it with anyone, apart from what Google's ad SDK collects for ad delivery — see “Ads & Consent” below.

## Ads & Consent

ReVoltVPN uses Google AdMob to display rewarded video ads. Where required — for users in the European Economic Area (EEA) and United Kingdom — a consent dialog provided by Google's User Messaging Platform is shown before the first ad is requested, letting you choose whether to allow personalized ads. This choice is stored on your device and can be reviewed at any time from the "Ad Consent" entry in the sidebar menu. Google may collect data as part of ad delivery. Please refer to Google's Privacy Policy for details.

## Your traffic

Your internet traffic is routed through our server located in Finland. We do not inspect, log, or store your traffic. We only track total bytes transferred per session for quota enforcement — no packet contents, no destination IPs, no DNS queries.

## Contact

If you have any questions, open an issue on the GitHub repository or join the Discord server.
