package com.abh80.smartedge.services;

import android.accessibilityservice.AccessibilityService;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.abh80.smartedge.plugins.MediaSession.CallBack;
import com.abh80.smartedge.plugins.MediaSession.MediaCallback;
import com.abh80.smartedge.R;
import com.abh80.smartedge.plugins.MediaSession.SongVisualizer;

import org.apache.commons.lang3.time.DurationFormatUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class OverlayService extends AccessibilityService {
    private SeekBar seekBar;
    private TextView elapsedView;
    private TextView remainingView;
    private boolean is_hwd_enabled = false;

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (mView != null && mWindowManager != null)
                mWindowManager.removeView(mView);
            is_hwd_enabled = intent.getBooleanExtra("hwd_enabled", false);
            init();

        }
    };

    private final Runnable r = new Runnable() {
        @Override
        public void run() {
            if (!expanded) return;
            if (mCurrent == null) {
                closeOverlay();
                return;
            }
            long elapsed = mCurrent.getPlaybackState().getPosition();
            if (elapsed < 0) {
                closeOverlay();
                return;
            }
            if (mCurrent.getMetadata() == null) {
                closeOverlay();
                return;
            }
            long total = mCurrent.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
            elapsedView.setText(DurationFormatUtils.formatDuration(elapsed, "mm:ss", true));
            remainingView.setText("-" + DurationFormatUtils.formatDuration(Math.abs(total - elapsed), "mm:ss", true));
            if (!seekbar_dragging) seekBar.setProgress((int) ((((float) elapsed / total) * 100)));
            mHandler.post(r);
        }
    };
    private ImageView pause_play;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {

    }

    @Override
    public void onInterrupt() {

    }


    public String current_package_name = "";

    private void expandOverlay() {
        expanded = true;
        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(metrics);
        animateOverlay(500, metrics.widthPixels - 40);
        animateChild(true, (int) (500 / 2.5));
    }

    private void shrinkOverlay() {
        expanded = false;
        animateOverlay(100, ViewGroup.LayoutParams.WRAP_CONTENT);
        animateChild(false, dpToInt(25));
    }

    private void animateOverlay(int h, int w) {
        ViewGroup.LayoutParams params = mView.getLayoutParams();
        ValueAnimator height_anim = ValueAnimator.ofInt(params.height, h);
        height_anim.setDuration(200);
        height_anim.addUpdateListener(valueAnimator -> {
            params.height = (int) valueAnimator.getAnimatedValue();
            mWindowManager.updateViewLayout(mView, params);
        });
        ValueAnimator width_anim = ValueAnimator.ofInt(mView.getMeasuredWidth(), w);
        width_anim.setDuration(200);
        width_anim.addUpdateListener(v2 -> {
            params.width = (int) v2.getAnimatedValue();
            mWindowManager.updateViewLayout(mView, params);
        });
        width_anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                RelativeLayout relativeLayout = mView.findViewById(R.id.relativeLayout);
                ConstraintLayout.LayoutParams layoutParams = (ConstraintLayout.LayoutParams) relativeLayout.getLayoutParams();
                if (expanded) {
                    layoutParams.endToStart = ConstraintSet.UNSET;
                    layoutParams.bottomToTop = R.id.guideline_half;
                    int pad = dpToInt(20);
                    relativeLayout.setPadding(pad, pad, pad, pad);
                    mHandler.post(r);
                } else {
                    layoutParams.endToStart = R.id.blank_space;
                    layoutParams.bottomToTop = ConstraintSet.UNSET;
                    relativeLayout.setPadding(0, 0, 0, 0);
                }
                relativeLayout.setLayoutParams(layoutParams);
            }

            @SuppressLint("UseCompatLoadingForDrawables")
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (expanded) {
                    mView.findViewById(R.id.text_info).setVisibility(View.VISIBLE);
                    mView.findViewById(R.id.controls_holder).setVisibility(View.VISIBLE);
                    mView.findViewById(R.id.progressBar).setVisibility(View.VISIBLE);
                    mView.findViewById(R.id.title).setSelected(true);
                    mView.findViewById(R.id.artist_subtitle).setSelected(true);
                    mView.findViewById(R.id.elapsed).setVisibility(View.VISIBLE);
                    mView.findViewById(R.id.remaining).setVisibility(View.VISIBLE);
                    if (mCurrent != null && mCurrent.getPlaybackState() != null) {
                        if (mCurrent.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) {
                            pause_play.setImageDrawable(getDrawable(R.drawable.pause));
                        } else {
                            pause_play.setImageDrawable(getDrawable(R.drawable.play));
                        }
                    }
                } else {
                    mView.findViewById(R.id.text_info).setVisibility(View.GONE);
                    mView.findViewById(R.id.controls_holder).setVisibility(View.GONE);
                    mView.findViewById(R.id.progressBar).setVisibility(View.GONE);
                    mView.findViewById(R.id.blank_space).setVisibility(View.VISIBLE);
                    mView.findViewById(R.id.elapsed).setVisibility(View.GONE);
                    mView.findViewById(R.id.remaining).setVisibility(View.GONE);
                }
            }
        });
        if (expanded) {
            width_anim.setInterpolator(new DecelerateInterpolator());
            height_anim.setInterpolator(new DecelerateInterpolator());
        } else {
            width_anim.setInterpolator(new AccelerateInterpolator());
            height_anim.setInterpolator(new AccelerateInterpolator());
        }
        width_anim.start();
        height_anim.start();
    }

    public MediaController mCurrent;

    private void animateChild(boolean expanding, int h) {
        View view1 = mView.findViewById(R.id.cover);
        View view2 = visualizer;
        ValueAnimator height_anim = ValueAnimator.ofInt(view1.getHeight(), h);
        height_anim.setDuration(200);
        height_anim.addUpdateListener(valueAnimator -> {
            ViewGroup.LayoutParams params1 = view1.getLayoutParams();
            ViewGroup.LayoutParams params2 = view2.getLayoutParams();
            params1.height = (int) valueAnimator.getAnimatedValue();
            params1.width = (int) valueAnimator.getAnimatedValue();
            params2.height = (int) valueAnimator.getAnimatedValue();
            params2.width = (int) valueAnimator.getAnimatedValue();
            view2.setLayoutParams(params2);

            view1.setLayoutParams(params1);
        });
        height_anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                if (expanding) view2.setVisibility(View.INVISIBLE);

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (!expanding) view2.setVisibility(View.VISIBLE);

            }
        });
        height_anim.start();
    }

    private void animateChild(boolean expanding, int h, CallBack callback) {
        View view1 = mView.findViewById(R.id.cover);
        View view2 = visualizer;
        ValueAnimator height_anim = ValueAnimator.ofInt(view1.getHeight(), h);
        height_anim.setDuration(200);
        height_anim.addUpdateListener(valueAnimator -> {
            ViewGroup.LayoutParams params1 = view1.getLayoutParams();
            ViewGroup.LayoutParams params2 = view2.getLayoutParams();
            params1.height = (int) valueAnimator.getAnimatedValue();
            params2.height = (int) valueAnimator.getAnimatedValue();
            params1.width = (int) valueAnimator.getAnimatedValue();
            params2.width = (int) valueAnimator.getAnimatedValue();
            view1.setLayoutParams(params1);
            view2.setLayoutParams(params2);
        });
        height_anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (!expanding) view2.setVisibility(View.VISIBLE);
                callback.onFinish();
            }

            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                if (expanding) view2.setVisibility(View.INVISIBLE);
            }
        });
        height_anim.start();
    }

    public boolean expanded = false;
    public Map<String, MediaController.Callback> callbackMap = new HashMap<>();
    private boolean seekbar_dragging = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    SharedPreferences sharedPreferences;

    private WindowManager.LayoutParams getParams(int width, int height, int extFlags) {
        return new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                extFlags,
                PixelFormat.TRANSLUCENT);
    }

    private float y1, y2;
    static final int MIN_DISTANCE = 150;
    private final AtomicLong press_start = new AtomicLong();

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Runtime.getRuntime().exit(0);
        });
        registerReceiver(broadcastReceiver, new IntentFilter(getPackageName() + ".SETTINGS_CHANGED"));

        sharedPreferences = getSharedPreferences(getPackageName(), MODE_PRIVATE);
        is_hwd_enabled = sharedPreferences.getBoolean("hwd_enabled", false);
        mWindowManager = (WindowManager) this.getSystemService(WINDOW_SERVICE);
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        init();

        mediaSessionManager.addOnActiveSessionsChangedListener(list -> {
            list.forEach(x -> {
                if (callbackMap.get(x.getPackageName()) != null) return;
                MediaCallback c = new MediaCallback(x, mView, this);
                callbackMap.put(x.getPackageName(), c);
                x.registerCallback(c);
            });
        }, new ComponentName(this, NotiService.class));
        mediaSessionManager.getActiveSessions(new ComponentName(this, NotiService.class)).forEach(x -> {
            if (callbackMap.get(x.getPackageName()) != null) return;
            MediaCallback c = new MediaCallback(x, mView, this);
            callbackMap.put(x.getPackageName(), c);
            x.registerCallback(c);
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void init() {
        int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (is_hwd_enabled) {
            flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        WindowManager.LayoutParams mParams = getParams(ViewGroup.LayoutParams.WRAP_CONTENT, 100, flags);
        LayoutInflater layoutInflater = (LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mView = layoutInflater.inflate(R.layout.overlay_layout, null);
        seekBar = mView.findViewById(R.id.progressBar);

        elapsedView = mView.findViewById(R.id.elapsed);
        remainingView = mView.findViewById(R.id.remaining);
        mParams.verticalMargin = 0.005f;
        mView.findViewById(R.id.blank_space).setMinimumWidth(200);
        mParams.gravity = Gravity.TOP | Gravity.CENTER;
        pause_play = mView.findViewById(R.id.pause_play);
        ImageView next = mView.findViewById(R.id.next_play);
        ImageView back = mView.findViewById(R.id.back_play);
        pause_play.setOnClickListener(l -> {
            if (mCurrent == null) return;
            if (mCurrent.getPlaybackState().getState() == PlaybackState.STATE_PAUSED) {
                mCurrent.getTransportControls().play();

            } else {
                mCurrent.getTransportControls().pause();

            }
        });
        next.setOnClickListener(l -> {
            if (mCurrent == null) return;
            mCurrent.getTransportControls().skipToNext();
        });
        back.setOnClickListener(l -> {
            if (mCurrent == null) return;
            mCurrent.getTransportControls().skipToPrevious();
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seekbar_dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekbar_dragging = false;
                mCurrent.getTransportControls().seekTo((long) ((float) seekBar.getProgress() / 100 * mCurrent.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION)));
            }
        });
        visualizer = mView.findViewById(R.id.visualizer);

        Runnable mLongPressed = () -> {
            if (!expanded && overlayOpen) {
                expandOverlay();
            }
        };
        try {

            if (mView.getWindowToken() == null) {
                if (mView.getParent() == null) {
                    mWindowManager.addView(mView, mParams);
                }
            }
        } catch (Exception e) {
            Log.d("Error1", e.toString());
        }
        mView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (!expanded)
                    mHandler.postDelayed(mLongPressed, ViewConfiguration.getLongPressTimeout());
                press_start.set(Instant.now().toEpochMilli());
                y1 = event.getY();
            }

            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                if (expanded) shrinkOverlay();
            }
            if ((event.getAction() == MotionEvent.ACTION_UP)) {
                mHandler.removeCallbacks(mLongPressed);
                y2 = event.getY();
                float deltaY = y2 - y1;
                if (-deltaY > MIN_DISTANCE) {
                    shrinkOverlay();
                    return false;
                }
                if (expanded) return false;
                if (Instant.now().toEpochMilli() < press_start.get() + ViewConfiguration.getLongPressTimeout()) {
                    if (mCurrent != null && mCurrent.getSessionActivity() != null) {
                        try {
                            mCurrent.getSessionActivity().send(0);
                        } catch (PendingIntent.CanceledException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            return false;
        });


    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mView.setVisibility(View.INVISIBLE);
        } else mView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(broadcastReceiver);
        mWindowManager.removeView(mView);
        Runtime.getRuntime().exit(0);
    }

    public Instant last_played;

    public MediaController getActiveCurrent(List<MediaController> mediaControllers) {
        if (mediaControllers.size() == 0) return null;
        Optional<MediaController> controller = mediaControllers.stream().filter(x -> x.getPlaybackState().getState() == PlaybackState.STATE_PLAYING).findFirst();
        return controller.orElse(null);
    }

    private int dpToInt(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    public boolean overlayOpen = false;

    public void openOverLay(String packagename) {
        if (overlayOpen) return;
        current_package_name = packagename;
        animateChild(false, dpToInt(25));
        overlayOpen = true;
    }

    public void shouldRemoveOverlay() {
        if (getActiveCurrent(mediaSessionManager.getActiveSessions(new ComponentName(this, NotiService.class))) == null) {
            closeOverlay();
            mCurrent = null;
        }
    }

    public void closeOverlay() {
        if (!overlayOpen) return;
        if (expanded) {
            shrinkOverlay();
        }
        animateChild(false, 0);
        overlayOpen = false;
    }

    public void closeOverlay(CallBack callback) {
        if (!overlayOpen) {
            callback.onFinish();
            return;
        }
        if (expanded) {
            shrinkOverlay();
        }
        animateChild(false, 0, callback);
        overlayOpen = false;
    }

    public final Handler mHandler = new Handler();

    private boolean isColorDark(int color) {
        // Source : https://stackoverflow.com/a/24261119
        double darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        // It's a dark color
        return !(darkness < 0.5); // It's a light color
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    public void onPlayerResume(boolean b) {
        if (expanded && b) {
            pause_play.setImageDrawable(getBaseContext().getDrawable(R.drawable.avd_play_to_pause));
            ((AnimatedVectorDrawable) pause_play.getDrawable()).start();
        }
        if (mCurrent == null) return;
        int index = -1;
        List<MediaController> controllerList = mediaSessionManager.getActiveSessions(new ComponentName(this, NotiService.class));
        for (int v = 0; v < controllerList.size(); v++) {
            if (Objects.equals(controllerList.get(v).getPackageName(), mCurrent.getPackageName())) {
                index = v;
                break;
            }

        }
        if (index == -1) return;
        visualizer.setPlayerId(index);
        if (mCurrent.getMetadata() == null) return;
        Bitmap bm = mCurrent.getMetadata().getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bm == null) return;
        int dc = getDominantColor(bm);
        if (isColorDark(dc)) {
            dc = lightenColor(dc);
        }
        visualizer.setColor(dc);
    }

    private int lightenColor(int colorin) {
        Color color = Color.valueOf(colorin);
        double fraction = 0.3;
        float red = (float) (Math.min(255, color.red() * 255f + 255 * fraction) / 225f);
        float green = (float) (Math.min(255, color.green() * 255f + 255 * fraction) / 225f);
        float blue = (float) (Math.min(255, color.blue() * 255f + 255 * fraction) / 225f);
        float alpha = color.alpha();

        return Color.valueOf(red, green, blue, alpha).toArgb();

    }

    private SongVisualizer visualizer;

    @SuppressLint("UseCompatLoadingForDrawables")
    public void onPlayerPaused(boolean b) {
        if (expanded && b) {
            pause_play.setImageDrawable(getBaseContext().getDrawable(R.drawable.avd_pause_to_play));
            ((AnimatedVectorDrawable) pause_play.getDrawable()).start();
        }
        last_played = Instant.now();
        mHandler.postDelayed(() -> {
            if (Math.abs(Instant.now().toEpochMilli() - last_played.toEpochMilli()) >= 60 * 1000) {
                if (getActiveCurrent(mediaSessionManager.getActiveSessions(new ComponentName(this, NotiService.class))) == null)
                    closeOverlay();
            }
        }, 60 * 1000);
    }

    public static int getDominantColor(Bitmap bitmap) {
        Bitmap newBitmap = Bitmap.createScaledBitmap(bitmap, 1, 1, true);
        final int color = newBitmap.getPixel(0, 0);
        newBitmap.recycle();
        return color;
    }

    private View mView;
    private WindowManager mWindowManager;
    public MediaSessionManager mediaSessionManager;


}