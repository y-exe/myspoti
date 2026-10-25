# Myspoti yexe

本家の UI、拡張 API、混在キューの LocalPlayer、Gradle のリリースビルドを利用するフォークです。

追加機能は YouTube Music のダウンロード補完、保存済み音源への切替、ダウンロード継続用の foreground service です。音源補完は ISRC・曲名・アーティスト・長さで検索し、プレビューがあれば録音を照合します。元の AAC/M4A・Opus/WebM を保持します。SpotiFLAC は本家 SDK の download.source 拡張として提供し、標準のインストール・認証画面を使用します。

通常は Spotify を優先し、オフライン・接続不良・推定 1 Mbps 以下・データセーバー時は保存音源へすぐ切り替えます。通常の再生開始待ちは最大 3 秒です。Android と Chromium の通信情報を使い、速度測定用の通信は行いません。

アプリ名は Myspoti、applicationId は net.yexe.myspoti です。base/fork.gradle に識別子と署名の設定を分離しています。署名鍵は既存と同じものを継続利用します。

## 履歴と自動更新

main は「本家 main のコミット + 署名付きのフォーク変更 1 コミット」を維持します。upstream-sync.yml が 6 時間ごとに本家を確認し、変更があればフォークの 1 コミットを最新の本家へ rebase し、署名を検証して force-with-lease で更新します。競合時は失敗として停止し、公開中の main とリリースを維持します。30 日間変更がない場合は同じコミットを署名付きで amend し、定期実行を維持します。

同期後は fork-release.yml を明示的に起動します。本家由来の JVM テストと SDK 検証が通ってから、本家の assembleRelease で署名済み APK と extension.zip を公開します。バージョン名は本家の直近タグに -yexe を追加します（例: 2.0.2-beta.5-yexe）。Android の versionCode は本家リリースの APK から読み取り、同じ値を使います。以前のフォーク版より値が小さくなる移行時は、Android が通常の上書き更新を拒否する場合があります。本家のベータ番号もそのままバージョン名に残しますが、フォークは同じアプリ ID の単一チャンネルとして Latest に公開します。本家の拡張インストーラーと既存版からの更新確認を維持するためです。同じ本家バージョンの再ビルドでは同じリリースを更新し、新しいリリース公開後に旧リリースとそのタグを削除します。

android.yml は本家のままです。独自に追加したテスト・実機診断ファイルは含めません。

必要な GitHub Secrets:

- MYSPOTI_STABLE_KEYSTORE_BASE64 / MYSPOTI_STABLE_KEYSTORE_PASSWORD / MYSPOTI_STABLE_KEY_ALIAS / MYSPOTI_STABLE_KEY_PASSWORD: 継続利用する APK 署名鍵。
- UPSTREAM_SYNC_SIGNING_KEY: 自動同期コミットの SSH 署名鍵。公開鍵は .github/sync-allowed-signers。

元の GPL-3.0 ライセンスと本家のクレジットを維持します。README.md と THIRD_PARTY_NOTICES.md を参照してください。
