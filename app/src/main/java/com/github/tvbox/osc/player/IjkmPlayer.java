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
        // 基础播放设置
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "safe", 0);
        // 设置协议白名单
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "ijkio,ffio,async,cache,crypto,file,dash,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data,concat,subfile,ffconcat");
    }

@Override
public void setDataSource(String path, Map<String, String> headers) {
    try {
        if (path == null || TextUtils.isEmpty(path)) return;

        // 预设逻辑：重置、注入参数
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", "");
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", "");
        applyCustomUAAndHeaders(headers);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "rtp,udp,tcp,tls,http,https,crypto,file,ijkio,ffio");

        // --- 修正点：确保 path 是字符串而不是数组 ---
        if (path.contains("#")) {
            path = path.split("#")[0]; 
        }

        // 处理中文路径
        path = encodeSpaceChinese(path);

        mMediaPlayer.setDataSource(path);

    } catch (Exception e) {
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError(-1, PlayerHelper.getRootCauseMessage(e));
        }
    }
}

private void applyCustomUAAndHeaders(Map<String, String> headers) {
    String customUA = Hawk.get(HawkConfig.CUSTOM_UA, "okHttp/Mod-1.5.0.0").trim();
    
    // 1. 核心：强制注入 IJK 内核专用 UA 字段
    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", customUA);

    // 2. 针对 HTTPS 和特定服务器的优化
    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "safe", 0);
    // 强制不验证证书（很多体育源证书过期或不规范）
    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "tls_verify", 0); 
    // 强制重定向时也携带这个 UA
    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-header-no-default-ua", 1);

    StringBuilder sb = new StringBuilder();
    // 3. 核心：在 headers 字符串中手动置顶 User-Agent
    sb.append("User-Agent: ").append(customUA).append("\r\n");

    if (headers != null && !headers.isEmpty()) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            // 排除其他可能的干扰项
            if (key.equalsIgnoreCase("User-Agent") || key.equalsIgnoreCase("user-agent")) continue;
            sb.append(key).append(": ").append(entry.getValue()).append("\r\n");
        }
    }

    if (sb.length() > 0) {
        // 将拼接好的所有 Header（含自定义 UA）传给 IJK
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", sb.toString());
    }
}

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        try {
            mMediaPlayer.setDataSource(new RawDataSourceProvider(fd));
        } catch (Exception e) {
            mPlayerEventListener.onError(-1, PlayerHelper.getRootCauseMessage(e));
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
