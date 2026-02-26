package com.github.tvbox.osc.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;
import xyz.doikki.videoplayer.ijk.IjkPlayer;
import xyz.doikki.videoplayer.ijk.RawDataSourceProvider;

public class IjkmPlayer extends IjkPlayer {

    private IJKCode codec = null;

    public IjkmPlayer(Context context, IJKCode codec) {
        super(context);
        this.codec = codec;
    }

    @Override
    public void setOptions() {
        // ... (保持原有 options 设置逻辑不变) ...
        IJKCode codecTmp = this.codec == null ? ApiConfig.get().getCurrentIJKCode() : this.codec;
        LinkedHashMap<String, String> options = codecTmp.getOption();
        if (options != null) {
            for (String key : options.keySet()) {
                String value = options.get(key);
                String[] opt = key.split("\\|");
                int category = Integer.parseInt(opt[0].trim());
                String name = opt[1].trim();
                try {
                    long valLong = Long.parseLong(value);
                    mMediaPlayer.setOption(category, name, valLong);
                } catch (Exception e) {
                    mMediaPlayer.setOption(category, name, value);
                }
            }
        }
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "safe", 0);
        // 注意：这里不要直接调 super.setOptions()，以免覆盖我们手动设的特殊参数
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            // 1. 处理路径逻辑（缓存等）
            if (path != null && !TextUtils.isEmpty(path)) {
                if(path.startsWith("rtsp")){
                    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 1);
                    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp");
                    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp");
                } else if (!path.contains(".m3u8") && (path.contains(".mp4") || path.contains(".mkv") || path.contains(".avi"))) {
                    if (Hawk.get(HawkConfig.IJK_CACHE_PLAY, false)) {
                        // ... (这里保留您原有的缓存逻辑) ...
                        String cachePath = FileUtils.getExternalCachePath() + "/ijkcaches/";
                        File cacheFile = new File(cachePath);
                        if (!cacheFile.exists()) cacheFile.mkdirs();
                        String tmpMd5 = MD5.string2MD5(path);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_file_path", cachePath + tmpMd5 + ".file");
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_map_path", cachePath + tmpMd5 + ".map");
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "parse_cache_map", 1);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "auto_save_map", 1);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_max_capacity", 60 * 1024 * 1024);
                        path = "ijkio:cache:ffio:" + path;
                    }
                }
            }

            // 核心修改：在 setDataSource 之前调用参数设置
            applyCustomHeaders(headers);

            // 核心修改：不再调用 super.setDataSource(path, headers)，因为 super 内部会重新创建参数
            // 直接调用 IJK 原始方法设置数据源
            mMediaPlayer.setDataSource(path);

        } catch (Exception e) {
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError(-1, PlayerHelper.getRootCauseMessage(e));
            }
        }
        // 协议白名单
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "ijkio,ffio,async,cache,crypto,file,dash,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data,concat,subfile,ffconcat");
    }

    private void applyCustomHeaders(Map<String, String> headers) {
        // 1. 获取全局设置的 UA
        String customUA = Hawk.get(HawkConfig.CUSTOM_UA, "");
        
        // 2. 设置 User-Agent
        if (!TextUtils.isEmpty(customUA)) {
            // IJKPlayer 识别的 User-Agent 必须通过这个 Option 传入
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", customUA);
        }

        // 3. 处理其他 Headers (如源自带的 Cookie)
        StringBuilder sb = new StringBuilder();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                // 如果是 UA，则跳过（使用我们全局设置的那个）
                if (key.equalsIgnoreCase("User-Agent") || key.equalsIgnoreCase("user-agent")) {
                    continue;
                }
                sb.append(key).append(": ").append(entry.getValue()).append("\r\n");
            }
        }

        // 注入非 UA 的其他 Header 字符串
        if (sb.length() > 0) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", sb.toString());
        }
    }

    private String encodeSpaceChinese(String str) throws UnsupportedEncodingException {
        Pattern p = Pattern.compile("[\u4e00-\u9fa5 ]+");
        Matcher m = p.matcher(str);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, URLEncoder.encode(m.group(0), "UTF-8"));
        m.appendTail(b);
        return b.toString();
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        try {
            mMediaPlayer.setDataSource(new RawDataSourceProvider(fd));
        } catch (Exception e) {
            mPlayerEventListener.onError(-1, PlayerHelper.getRootCauseMessage(e));
        }
    }

    public TrackInfo getTrackInfo() {
        IjkTrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
        if (trackInfo == null) return null;
        TrackInfo data = new TrackInfo();
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int index = 0;
        for (IjkTrackInfo info : trackInfo) {
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                String trackName = (data.getAudio().size() + 1) + "：" + info.getInfoInline();
                TrackInfoBean t = new TrackInfoBean();
                t.name = trackName;
                t.language = info.getLanguage();
                t.trackId = index;
                t.selected = index == audioSelected;
                data.addAudio(t);
            }
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                String trackName = (data.getSubtitle().size() + 1) + "：" + info.getInfoInline();
                TrackInfoBean t = new TrackInfoBean();
                t.name = trackName;
                t.language = info.getLanguage();
                t.trackId = index;
                t.selected = index == subtitleSelected;
                data.addSubtitle(t);
            }
            index++;
        }
        return data;
    }

    public void setTrack(int trackIndex) {
        mMediaPlayer.selectTrack(trackIndex);
    }

    public void setOnTimedTextListener(IMediaPlayer.OnTimedTextListener listener) {
        mMediaPlayer.setOnTimedTextListener(listener);
    }
}
