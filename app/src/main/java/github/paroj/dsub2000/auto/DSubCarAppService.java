package github.paroj.dsub2000.auto;

import android.content.ComponentName;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SessionInfo;
import androidx.car.app.validation.HostValidator;
import androidx.car.app.media.MediaPlaybackManager;
import androidx.car.app.media.model.MediaPlaybackTemplate;
import android.support.v4.media.MediaBrowserCompat;

import github.paroj.dsub2000.BuildConfig;
import github.paroj.dsub2000.service.DownloadService;

/** Experimental Android Auto template entry point; retains the legacy media service. */
public final class DSubCarAppService extends CarAppService {
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // Only allow unrestricted host discovery in locally installed debug builds.
        if (BuildConfig.DEBUG) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        }
        return new HostValidator.Builder(this).build();
    }

    @NonNull
    @Override
    public Session onCreateSession(@NonNull SessionInfo sessionInfo) {
        return new Session() {
            private MediaBrowserCompat browser;

            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull android.content.Intent intent) {
                browser = new MediaBrowserCompat(getCarContext(),
                        new ComponentName(getCarContext(), DownloadService.class),
                        new MediaBrowserCompat.ConnectionCallback() {
                            @Override
                            public void onConnected() {
                                MediaPlaybackManager manager = (MediaPlaybackManager)
                                        getCarContext().getCarService(CarContext.MEDIA_PLAYBACK_SERVICE);
                                manager.registerMediaPlaybackToken(browser.getSessionToken());
                            }
                        }, null);
                browser.connect();
                return new Screen(getCarContext()) {
                    @NonNull
                    @Override
                    public androidx.car.app.model.Template onGetTemplate() {
                        return new MediaPlaybackTemplate.Builder().build();
                    }
                };
            }

            {
                getLifecycle().addObserver(
                        new androidx.lifecycle.DefaultLifecycleObserver() {
                            @Override
                            public void onDestroy(
                                    @NonNull androidx.lifecycle.LifecycleOwner owner) {
                                if (browser != null) {
                                    browser.disconnect();
                                    browser = null;
                                }
                            }
                        });
            }
        };
    }
}
