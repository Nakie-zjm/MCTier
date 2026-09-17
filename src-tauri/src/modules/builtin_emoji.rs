use futures_util::{stream, StreamExt};
use serde::Serialize;
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};
use std::time::Duration;
use tauri::{Emitter, Manager};
use tokio::io::AsyncReadExt;

const SOURCE_PAGE: &str =
    "https://www.emojiall.com/zh-hans/image-emoji-platform/telegram/animation";
const ASSET_ROOT: &str = "https://www.emojiall.com/images/120/telegram";
const MAX_PAGE_BYTES: usize = 2 * 1024 * 1024;
const MAX_GIF_BYTES: usize = 4 * 1024 * 1024;
const MAX_EMOJI_COUNT: usize = 2_000;
const MIN_EMOJI_COUNT: usize = 100;
const DOWNLOAD_CONCURRENCY: usize = 10;
static SYNC_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BuiltinEmojiAsset {
    id: String,
    name: String,
    path: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct BuiltinEmojiProgress {
    downloaded: usize,
    total: usize,
}

fn emit_progress(app: &tauri::AppHandle, downloaded: usize, total: usize) {
    let _ = app.emit(
        "builtin-emoji-progress",
        BuiltinEmojiProgress { downloaded, total },
    );
}

async fn read_limited(response: reqwest::Response, limit: usize) -> Result<Vec<u8>, String> {
    if response
        .content_length()
        .is_some_and(|length| length > limit as u64)
    {
        return Err("远程表情资源超过大小限制".into());
    }
    let mut output = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|error| format!("读取表情资源失败: {error}"))?;
        let Some(next_length) = output.len().checked_add(chunk.len()) else {
            return Err("远程表情资源超过大小限制".into());
        };
        if next_length > limit {
            return Err("远程表情资源超过大小限制".into());
        }
        output.extend_from_slice(&chunk);
    }
    Ok(output)
}

fn parse_ids(html: &str) -> Result<Vec<String>, String> {
    let pattern = regex::Regex::new(r#"/images/120/telegram/([A-Za-z0-9_-]+)\.gif"#)
        .map_err(|error| error.to_string())?;
    let mut ids = Vec::new();
    for capture in pattern.captures_iter(html) {
        let id = capture[1].to_string();
        if !ids.contains(&id) {
            ids.push(id);
        }
        if ids.len() > MAX_EMOJI_COUNT {
            return Err("远程表情数量异常".into());
        }
    }
    if ids.len() < MIN_EMOJI_COUNT {
        return Err("未能从页面解析出完整表情列表".into());
    }
    Ok(ids)
}

fn assets(cache_dir: &Path, ids: &[String]) -> Vec<BuiltinEmojiAsset> {
    ids.iter()
        .map(|id| BuiltinEmojiAsset {
            id: format!("builtin-{id}"),
            name: id.clone(),
            path: cache_dir
                .join(format!("{id}.gif"))
                .to_string_lossy()
                .into_owned(),
        })
        .collect()
}

async fn valid_cached_gif(path: &Path) -> bool {
    let Ok(metadata) = tokio::fs::metadata(path).await else {
        return false;
    };
    if !metadata.is_file() || metadata.len() < 6 || metadata.len() > MAX_GIF_BYTES as u64 {
        return false;
    }
    let Ok(mut file) = tokio::fs::File::open(path).await else {
        return false;
    };
    let mut header = [0u8; 6];
    file.read_exact(&mut header).await.is_ok() && (&header == b"GIF87a" || &header == b"GIF89a")
}

async fn cached_index(path: &Path) -> Option<Vec<String>> {
    if tokio::fs::metadata(path).await.ok()?.len() > 128 * 1024 {
        return None;
    }
    let index = tokio::fs::read_to_string(path).await.ok()?;
    let ids = index
        .lines()
        .filter(|line| !line.is_empty())
        .map(str::to_string)
        .collect::<Vec<_>>();
    if ids.len() < MIN_EMOJI_COUNT || ids.len() > MAX_EMOJI_COUNT {
        return None;
    }
    let mut unique_ids = HashSet::with_capacity(ids.len());
    for id in &ids {
        if !unique_ids.insert(id)
            || !id.chars().all(|character| {
                character.is_ascii_alphanumeric() || character == '_' || character == '-'
            })
        {
            return None;
        }
    }
    Some(ids)
}

async fn completed_cache(cache_dir: &Path) -> Option<Vec<BuiltinEmojiAsset>> {
    let ids = cached_index(&cache_dir.join("complete-v1.txt")).await?;
    for id in &ids {
        let path = cache_dir.join(format!("{id}.gif"));
        if !valid_cached_gif(&path).await {
            return None;
        }
    }
    Some(assets(cache_dir, &ids))
}

async fn complete_download_batch<F: std::future::Future<Output = Result<(), String>>>(
    tasks: impl IntoIterator<Item = F>,
) -> Result<(), String> {
    let results = stream::iter(tasks)
        .buffer_unordered(DOWNLOAD_CONCURRENCY)
        .collect::<Vec<_>>()
        .await;
    // A failed resource must not cancel the other downloads or discard their cache.
    results.into_iter().collect()
}

async fn download_one(
    client: reqwest::Client,
    cache_dir: PathBuf,
    id: String,
) -> Result<(), String> {
    let destination = cache_dir.join(format!("{id}.gif"));
    if valid_cached_gif(&destination).await {
        return Ok(());
    }
    let url = format!("{ASSET_ROOT}/{id}.gif");
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|error| format!("下载表情失败: {error}"))?;
    if !response.status().is_success() {
        return Err(format!("下载表情失败: HTTP {}", response.status()));
    }
    let bytes = read_limited(response, MAX_GIF_BYTES).await?;
    if !(bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a")) {
        return Err("远程资源不是有效 GIF".into());
    }
    let temporary = cache_dir.join(format!("{id}.tmp"));
    tokio::fs::write(&temporary, bytes)
        .await
        .map_err(|error| format!("写入表情缓存失败: {error}"))?;
    if tokio::fs::rename(&temporary, &destination).await.is_err() {
        let _ = tokio::fs::remove_file(&destination).await;
        tokio::fs::rename(&temporary, &destination)
            .await
            .map_err(|error| format!("提交表情缓存失败: {error}"))?;
    }
    Ok(())
}

#[tauri::command]
pub async fn sync_builtin_emoji(app: tauri::AppHandle) -> Result<Vec<BuiltinEmojiAsset>, String> {
    let _guard = SYNC_LOCK.lock().await;
    let cache_dir = app
        .path()
        .app_cache_dir()
        .map_err(|error| format!("无法定位应用缓存目录: {error}"))?
        .join("emoji-builtin-v1");
    tokio::fs::create_dir_all(&cache_dir)
        .await
        .map_err(|error| format!("无法创建表情缓存目录: {error}"))?;
    if let Some(cached) = completed_cache(&cache_dir).await {
        emit_progress(&app, cached.len(), cached.len());
        return Ok(cached);
    }

    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(12))
        .timeout(Duration::from_secs(35))
        .redirect(reqwest::redirect::Policy::none())
        .user_agent("MCTier/3.4 emoji-cache")
        .build()
        .map_err(|error| format!("无法创建表情下载客户端: {error}"))?;
    let ids = if let Some(ids) = cached_index(&cache_dir.join("index-v1.txt")).await {
        ids
    } else {
        let response = client
            .get(SOURCE_PAGE)
            .send()
            .await
            .map_err(|error| format!("获取表情页面失败: {error}"))?;
        if !response.status().is_success() {
            return Err(format!("获取表情页面失败: HTTP {}", response.status()));
        }
        let html = String::from_utf8(read_limited(response, MAX_PAGE_BYTES).await?)
            .map_err(|_| "表情页面编码无效".to_string())?;
        let ids = parse_ids(&html)?;
        tokio::fs::write(cache_dir.join("index-v1.txt"), ids.join("\n"))
            .await
            .map_err(|error| format!("保存表情下载索引失败: {error}"))?;
        ids
    };
    let total = ids.len();
    let mut pending = Vec::new();
    for id in &ids {
        if !valid_cached_gif(&cache_dir.join(format!("{id}.gif"))).await {
            pending.push(id.clone());
        }
    }
    let completed = Arc::new(AtomicUsize::new(total - pending.len()));
    emit_progress(&app, completed.load(Ordering::Relaxed), total);

    complete_download_batch(pending.into_iter().map(|id| {
        let app = app.clone();
        let client = client.clone();
        let cache_dir = cache_dir.clone();
        let completed = Arc::clone(&completed);
        async move {
            download_one(client, cache_dir, id).await?;
            let downloaded = completed.fetch_add(1, Ordering::Relaxed) + 1;
            emit_progress(&app, downloaded, total);
            Ok::<(), String>(())
        }
    }))
    .await?;
    let marker = cache_dir.join("complete-v1.txt");
    let temporary_marker = cache_dir.join("complete-v1.tmp");
    tokio::fs::write(&temporary_marker, ids.join("\n"))
        .await
        .map_err(|error| format!("写入表情缓存索引失败: {error}"))?;
    if tokio::fs::rename(&temporary_marker, &marker).await.is_err() {
        let _ = tokio::fs::remove_file(&marker).await;
        tokio::fs::rename(&temporary_marker, &marker)
            .await
            .map_err(|error| format!("提交表情缓存索引失败: {error}"))?;
    }
    Ok(assets(&cache_dir, &ids))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn ten_downloads_run_in_parallel_and_one_failure_does_not_cancel_the_batch() {
        let active = AtomicUsize::new(0);
        let peak = AtomicUsize::new(0);
        let finished = AtomicUsize::new(0);
        let result = complete_download_batch((0..25).map(|index| {
            let (active, peak, finished) = (&active, &peak, &finished);
            async move {
                let count = active.fetch_add(1, Ordering::SeqCst) + 1;
                peak.fetch_max(count, Ordering::SeqCst);
                tokio::time::sleep(Duration::from_millis(10)).await;
                active.fetch_sub(1, Ordering::SeqCst);
                finished.fetch_add(1, Ordering::SeqCst);
                if index == 0 {
                    Err("temporary failure".into())
                } else {
                    Ok(())
                }
            }
        }))
        .await;
        assert!(result.is_err());
        assert_eq!(peak.load(Ordering::SeqCst), 10);
        assert_eq!(finished.load(Ordering::SeqCst), 25);
    }

    #[test]
    fn parser_extracts_safe_ids_and_deduplicates_them() {
        let mut html = String::new();
        for index in 0..MIN_EMOJI_COUNT {
            html.push_str(&format!(
                r#"<img src="/images/120/telegram/{index:x}.gif">"#
            ));
        }
        html.push_str(
            r#"<img src="/images/120/telegram/0.gif"><img src="/images/120/other/no.gif">"#,
        );
        let ids = parse_ids(&html).unwrap();
        assert_eq!(ids.len(), MIN_EMOJI_COUNT);
    }

    #[tokio::test]
    async fn completed_cache_requires_every_indexed_file_to_be_a_gif() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let cache_dir = std::env::temp_dir().join(format!(
            "mctier-builtin-emoji-{}-{unique}",
            std::process::id()
        ));
        tokio::fs::create_dir_all(&cache_dir).await.unwrap();
        let ids = (0..MIN_EMOJI_COUNT)
            .map(|index| format!("{index:x}"))
            .collect::<Vec<_>>();
        for id in &ids {
            tokio::fs::write(cache_dir.join(format!("{id}.gif")), b"GIF89a")
                .await
                .unwrap();
        }
        tokio::fs::write(cache_dir.join("complete-v1.txt"), ids.join("\n"))
            .await
            .unwrap();

        tokio::fs::write(cache_dir.join("index-v1.txt"), ids.join("\n"))
            .await
            .unwrap();
        assert_eq!(
            cached_index(&cache_dir.join("index-v1.txt")).await.unwrap(),
            ids
        );

        assert_eq!(completed_cache(&cache_dir).await.unwrap().len(), ids.len());
        tokio::fs::write(cache_dir.join("0.gif"), b"<html>")
            .await
            .unwrap();
        assert!(completed_cache(&cache_dir).await.is_none());
        tokio::fs::remove_dir_all(cache_dir).await.unwrap();
    }
}
