package com.ijk.media.player.lib;

import android.content.Context;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.io.IOException;

import tv.danmaku.ijk.media.ijkplayerlib.R;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.MediaPlayerProxy;

/**
 * Created by jiatao on 2017/2/20.
 */

public class ReplayVideoView extends LinearLayout {
    private static final String TAG = "UVideoView";

    private Context mContext;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private PowerManager.WakeLock mWakeLock;

    private MediaPlayerProxy mCurrentPlayer;

    private int mPlayerRetryCount;

    private OnStateListener mStateListener;
    private String mStreamUrl;
    private State mState;

    private IjkMediaPlayer ijkMediaPlayer;

    private boolean mIsPlaying;
    private boolean mBackNeedReplay;
    private boolean mSeekToFlag = false;

    private Orientation mOrientation = Orientation.PORTRAIT;

    //由于业务需求，暂时只提供这些方法
    public interface OnStateListener {
        //开始连接的时候调用这个方法，包括请求play list和播放视频流
        void onConnecting();
        //开始loading或者播放错误调用这个方法
        void onReconnecting();
        //播放过程中出现错误调用这个方法，实际上这个版本永远不会调用这个方法，因为会一直重试
        void onStop(int error);
        //视频真正播放的时候调用这个方法
        void onPlaying(boolean isFirst);
        //重新打开视频流
        void onReopenStreamFail(int count);
    }

    public enum State {
        //初始状态
        INIT,
        //已经初始化
        PREPARED,
        //请求play list
        REQUESTING,
        //播放器打开流
        OPENING,
        //正在播放
        PLAYING,
        //播放loading
        LOADING,
        //播放错误重新打开流
        REOPENING,
        //播放暂停
        PAUSE
    }

    public ReplayVideoView(Context context) {
        this(context, null);
    }

    public ReplayVideoView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ReplayVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        initView();
    }

    private void initView() {
        MyLog.d(TAG, "Init View");
        mSurfaceView = new SurfaceView(mContext);
        LayoutParams layoutParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT
                , ViewGroup.LayoutParams.MATCH_PARENT);
        mSurfaceView.setLayoutParams(layoutParams);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(mSurfaceHolderCallback);
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mSurfaceView.setZOrderOnTop(false);
        mSurfaceView.setZOrderMediaOverlay(false);
        addView(mSurfaceView);

        PowerManager mPowerManager = ((PowerManager) mContext.getSystemService(getContext().POWER_SERVICE));
        mWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                PowerManager.ON_AFTER_RELEASE, TAG);
        mState = State.PREPARED;
    }

    public void setStateListener(OnStateListener listener) {
        mStateListener = listener;
    }


    public void setPlayUrl(String playerUrl) {
        mStreamUrl = playerUrl;
    }

    public void play() {
        if(mState != State.PREPARED) {
            MyLog.d(TAG, "Player isn't in prepared state, so needn't to play this time");
            return;
        }

        mIsPlaying = true;
        mState = State.OPENING;
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }

        if(mStateListener != null) {
            mStateListener.onConnecting();
        }

        if(!TextUtils.isEmpty(mStreamUrl)) {
            MyLog.d(TAG, "Fast play at " + System.currentTimeMillis());
            mState = State.OPENING;
            doVideoPlay(mStreamUrl);
        }
    }

    private void initLandscapeSurfaceSize(int videoWidth, int videoHeight) {
        LayoutParams layoutParams = (LayoutParams) mSurfaceView.getLayoutParams();
        int leftMargin = 0, topMargin = 0;
        int width = Utils.getScreenWidth(mContext);
        int height = Utils.getScreenHeight(mContext);
        if (width < height) {// 解决横屏直播间先初始化时是竖屏,后才调用onconfigchang改变方向导致的宽高相反
            width = height + width;
            height = width - height;
            width = width - height;
        }
        float widthRatio = (float) width / (float) videoWidth;
        float heightRatio = (float) height / (float) videoHeight;
        if (widthRatio == heightRatio) {
            layoutParams.width = width;
            layoutParams.height = height;
        } else if (widthRatio > heightRatio) {
            int finalWidth = (int) (videoWidth * heightRatio);
            layoutParams.width = finalWidth;
            layoutParams.height = height;
            leftMargin = (width - finalWidth) / 2;
        } else {
            int finalHeight = (int) (videoHeight * widthRatio);
            layoutParams.width = width;
            layoutParams.height = finalHeight;
            topMargin = (height - finalHeight) / 2;
        }

        MyLog.i(TAG, "initSurfaceSize[]>>>>>final height = " + layoutParams.height);
        MyLog.i(TAG, "initSurfaceSize[]>>>>>final width = " + layoutParams.width);

        layoutParams.leftMargin = leftMargin;
        layoutParams.topMargin = topMargin;
        mSurfaceView.setLayoutParams(layoutParams);
    }

    public void setOrientation(Orientation orientation) {
        mOrientation = orientation;
    }

    private void doVideoPlay(String url) {
        if (mCurrentPlayer != null) {
            mCurrentPlayer.reset();
            mCurrentPlayer.release();
            mCurrentPlayer = null;
        }

        MyLog.d(TAG, "Init Player and start to Prepare at " + System.currentTimeMillis());
        MyLog.d(TAG, "Stream url: " + url);

        //设置背景透明-因为在网络断开后将Canvas置黑色，再次初始化时必须再次置为透明的！！！
        //surfaceView.getHolder().setFormat(PixelFormat.TRANSPARENT);
        mCurrentPlayer = new MediaPlayerProxy(getFastPlayer());
        mCurrentPlayer.setOnPreparedListener(mPreparedListener);
        mCurrentPlayer.setOnCompletionListener(mCompletionListener);
        mCurrentPlayer.setOnInfoListener(mInfoListener);
        mCurrentPlayer.setOnErrorListener(mErrorListener);
        try {
            mCurrentPlayer.setDataSource(url);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCurrentPlayer.setDisplay(mSurfaceHolder);
        mCurrentPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mCurrentPlayer.setScreenOnWhilePlaying(true);
        mCurrentPlayer.prepareAsync();
    }

    private IjkMediaPlayer getFastPlayer() {
        ijkMediaPlayer = new IjkMediaPlayer();

        if (DebugHelp.isDebugBuild()) {
            ijkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_WARN);//打开播放器LOG
        } else {
            ijkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_SILENT);//关掉播放器LOG
        }

        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        //直播时缓存buffer的丢包门限,默认1200000ms,范围为100~1200000ms,TCP连麦时可以设置为100~1000
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mindropthreshold", 1200000);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "maxdropthreshold", 1200000);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);//set true for quickly start
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", 2);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "fflags", "low_delay");

        return ijkMediaPlayer;
    }


    private IMediaPlayer.OnPreparedListener mPreparedListener = new IMediaPlayer.OnPreparedListener() {
        public void onPrepared(IMediaPlayer mp) {
//            MyLog.d(TAG, "Player Prepared at " + System.currentTimeMillis());
            mp.start();
        }
    };

    private IMediaPlayer.OnInfoListener mInfoListener = new IMediaPlayer.OnInfoListener() {
        public boolean onInfo(IMediaPlayer mp, int arg1, int arg2) {
            switch (arg1) {
                case IMediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING:
                    MyLog.d(TAG, "MEDIA_INFO_VIDEO_TRACK_LAGGING:");
                    break;
                case IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                    MyLog.d(TAG, "MEDIA_INFO_VIDEO_RENDERING_START:");
                    videoStart();
                    break;
                case IMediaPlayer.MEDIA_INFO_BUFFERING_START:
                    MyLog.d(TAG, "MEDIA_INFO_BUFFERING_START:");
                    startLoading();
                    break;
                case IMediaPlayer.MEDIA_INFO_BUFFERING_END:
                    MyLog.d(TAG, "MEDIA_INFO_BUFFERING_END:");
                    endLoading();
                    break;
                case IMediaPlayer.MEDIA_INFO_NETWORK_BANDWIDTH:
                    MyLog.d(TAG, "MEDIA_INFO_NETWORK_BANDWIDTH: " + arg2);
                    break;
                case IMediaPlayer.MEDIA_INFO_BAD_INTERLEAVING:
                    MyLog.d(TAG, "MEDIA_INFO_BAD_INTERLEAVING:");
                    break;
                case IMediaPlayer.MEDIA_INFO_NOT_SEEKABLE:
                    MyLog.d(TAG, "MEDIA_INFO_NOT_SEEKABLE:");
                    break;
                case IMediaPlayer.MEDIA_INFO_METADATA_UPDATE:
                    MyLog.d(TAG, "MEDIA_INFO_METADATA_UPDATE:");
                    break;
                case IMediaPlayer.MEDIA_INFO_UNSUPPORTED_SUBTITLE:
                    MyLog.d(TAG, "MEDIA_INFO_UNSUPPORTED_SUBTITLE:");
                    break;
                case IMediaPlayer.MEDIA_INFO_SUBTITLE_TIMED_OUT:
                    MyLog.d(TAG, "MEDIA_INFO_SUBTITLE_TIMED_OUT:");
                    break;
                case IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED:
                    MyLog.d(TAG, "MEDIA_INFO_VIDEO_ROTATION_CHANGED: " + arg2);
                    break;
                case IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START:
                    MyLog.d(TAG, "MEDIA_INFO_AUDIO_RENDERING_START:");
                    break;
            }
            return true;
        }
    };

    private void videoStart() {
        MyLog.d(TAG, "Video start at " + System.currentTimeMillis());

        //重新设置surface view 尺寸
        if(mCurrentPlayer != null) {
            int vHeight = mCurrentPlayer.getVideoHeight();
            int vWidth = mCurrentPlayer.getVideoWidth();
            MyLog.d(TAG, "Height: " + vHeight + "   Width: " + vWidth);
            initSurfaceSize(vWidth, vHeight);
        }

        mState = State.PLAYING;
        if(mStateListener != null) {
            mStateListener.onPlaying(true);
        }
        mPlayerRetryCount = 0;

    }

    private void startLoading() {
        if(mState != State.PLAYING) {
            return;
        }
        MyLog.d(TAG, "Player start loading");
        mState = State.LOADING;
        if(mStateListener != null) {
            mStateListener.onReconnecting();
        }
    }

    private void endLoading() {
        if(mState != State.LOADING) {
            return;
        }
        MyLog.d(TAG, "Player end loading");
        mState = State.PLAYING;
        mPlayerRetryCount = 0;
        if(mStateListener != null) {
            mStateListener.onPlaying(false);
        }
    }

    private IMediaPlayer.OnCompletionListener mCompletionListener = new IMediaPlayer.OnCompletionListener() {
        public void onCompletion(IMediaPlayer mp) {
            MyLog.d(TAG, "Player complete");
            //TODO 后期处理
//            EventBus.getDefault().post(new ViewerLiveEvents.PlayerCompleteEvent());
        }
    };

    private IMediaPlayer.OnErrorListener mErrorListener = new IMediaPlayer.OnErrorListener() {
        public boolean onError(IMediaPlayer mp, int framework_err, int impl_err) {
            MyLog.d(TAG, "Error: " + framework_err + "," + impl_err);
            videoError();
            return true;
        }
    };

    private void videoError() {
        mPlayerRetryCount++;
        if(mState == State.PLAYING) {
            if(mStateListener != null) {
                mStateListener.onReconnecting();
            }
        }
        if(mStateListener != null) {
            mStateListener.onReopenStreamFail(mPlayerRetryCount);
        }
    }

    private void initSurfaceSize(int videoWidth, int videoHeight) {
        if(mOrientation == Orientation.LANDSCAPE) {
            initLandscapeSurfaceSize(videoWidth, videoHeight);
        } else {
            if(videoWidth < videoHeight) {
                initPortraitFullSurfaceView(videoWidth, videoHeight);
            } else {
                initPortraitNormalSurfaceView(videoWidth, videoHeight);
            }
        }
    }

    private void initPortraitFullSurfaceView(int videoWidth, int videoHeight) {
        LayoutParams layoutParams = (LayoutParams) mSurfaceView.getLayoutParams();
        int leftMargin = 0;
        int topMargin = 0;

        int surfaceWidth = Utils.getScreenWidth(mContext);
        int surfaceHeight = Utils.getScreenHeight(mContext) - Utils.getStatusBarHeight(mContext);

        float widthRatio = (float)surfaceWidth / (float)videoWidth;
        float heightRatio = (float)surfaceHeight / (float)videoHeight;
        if(widthRatio == heightRatio) {
            layoutParams.width = surfaceWidth;
            layoutParams.height = surfaceHeight;
            return;
        }else if(widthRatio > heightRatio) {
            int finalHeight = (int)(videoHeight * widthRatio);
            layoutParams.width = surfaceWidth;
            layoutParams.height = finalHeight;
            topMargin = -(finalHeight - surfaceHeight)/2;
        }else {
            int finalWidth = (int)(videoWidth * heightRatio);
            layoutParams.width = finalWidth;
            layoutParams.height = surfaceHeight;
            leftMargin = -(finalWidth - surfaceWidth)/2;
        }
        layoutParams.leftMargin = leftMargin;
        layoutParams.topMargin = topMargin;
        mSurfaceView.setLayoutParams(layoutParams);
    }

    private void initPortraitNormalSurfaceView(int videoWidth, int videoHeight) {
        setBackgroundResource(R.drawable.bg_uvideo_view);
        LayoutParams layoutParams = (LayoutParams) mSurfaceView.getLayoutParams();
//        int topMargin = Utils.DpToPx(92);
        int topMargin = Utils.dip2px(mContext,92);
        int surfaceWidth = Utils.getScreenWidth(mContext);
        float ratio = (float) videoHeight / (float) videoWidth;
        int surfaceHeight = (int)(surfaceWidth * ratio);
        layoutParams.width = surfaceWidth;
        layoutParams.height = surfaceHeight;
        layoutParams.topMargin = topMargin;
        mSurfaceView.setLayoutParams(layoutParams);
    }

    public void stop() {
        MyLog.d(TAG, "Player Stop");
        mIsPlaying = false;
        mSeekToFlag = false;
        if(mState == State.INIT || mState == State.PREPARED) {
            MyLog.d(TAG, "Player isn't in playing, so needn't stop");
            return;
        }
        mState = State.PREPARED;
        if(mCurrentPlayer != null) {
            mCurrentPlayer.reset();
            mCurrentPlayer.release();
            mCurrentPlayer = null;
        }
        if(mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    }


    public State getState() {
        return mState;
    }

    public void release() {
        MyLog.d(TAG, "Player Release");
        if(mState != State.PREPARED) {
            MyLog.d(TAG, "Player isn't in prepared, so needn't release");
            return;
        }
        mWakeLock = null;
        mState = State.INIT;
    }


    SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        public void surfaceChanged(SurfaceHolder holder, int format, int w,
                                   int h) {
            MyLog.i(TAG, "surfaceChanged{}>>>>>>>TIME = " + System.currentTimeMillis());
            MyLog.i(TAG, "surfaceChanged[]>>>>>>>width = " + w);
            MyLog.i(TAG, "surfaceChanged[]>>>>>>>height = " + h);

            Rect rect = mSurfaceView.getHolder().getSurfaceFrame();
            MyLog.i(TAG, "surfaceChanged{}>>>>>>>holder rect left = " + rect.left);
            MyLog.i(TAG, "surfaceChanged{}>>>>>>>holder rect top = " + rect.top);
            MyLog.i(TAG, "surfaceChanged{}>>>>>>>holder rect right = " + rect.right);
            MyLog.i(TAG, "surfaceChanged{}>>>>>>>holder rect bottom = " + rect.bottom);

            int mW = mSurfaceView.getWidth();
            int mH = mSurfaceView.getHeight();
            MyLog.i(TAG, "surfaceChanged[]>>>>>>>SurfaceView width = " + mW);
            MyLog.i(TAG, "surfaceChanged[]>>>>>>SurfaceView height = " + mH);
        }

        public void surfaceCreated(SurfaceHolder holder) {
            mSurfaceHolder = holder;
            if(mCurrentPlayer != null) {
                mCurrentPlayer.setDisplay(mSurfaceHolder);
            }
        }

        public void surfaceDestroyed(SurfaceHolder holder) {
        }
    };


    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public long getDuration() {
        if (ijkMediaPlayer != null) {
            return ijkMediaPlayer.getDuration();
        }
        return 0;
    }

    public long getCurrentPosition() {
        if (ijkMediaPlayer != null) {
            return ijkMediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    public void seekTo(long position) {
        if (ijkMediaPlayer != null) {
            mSeekToFlag = true;
            ijkMediaPlayer.seekTo(position);
        }
    }

    public void pause() {
        if (ijkMediaPlayer != null) {
            ijkMediaPlayer.pause();
        }
        mState = State.PAUSE;
    }


    public void start() {
        if (ijkMediaPlayer != null) {
            ijkMediaPlayer.start();
        }
    }

    public boolean isPlaying() {
        if (ijkMediaPlayer != null) {
            return ijkMediaPlayer.isPlaying();
        }
        return false;
    }

    public void setOnCompletionListener(IMediaPlayer.OnCompletionListener listener) {
        if (ijkMediaPlayer != null) {
            ijkMediaPlayer.setOnCompletionListener(listener);
        }
    }

//    public long getPlayableDuration() {
//        if (ijkMediaPlayer != null) {
//            return ijkMediaPlayer.getPlayableDuration();
//        }
//        return 0;
//    }

    public boolean isPlayingFlag() {
        return this.mIsPlaying;
    }

    public boolean backNeedReplay() {
        return mBackNeedReplay;
    }

    public void setBackNeedReplay(boolean backNeedReplay) {
        mBackNeedReplay = backNeedReplay;
    }

    public boolean seekToFlag() {
        return mSeekToFlag;
    }

    public enum Orientation {
        LANDSCAPE,
        PORTRAIT
    }

}
