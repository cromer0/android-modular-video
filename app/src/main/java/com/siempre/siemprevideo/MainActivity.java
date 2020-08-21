package com.siempre.siemprevideo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.WriteBatch;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;
import com.twilio.video.Camera2Capturer;
import com.twilio.video.CameraCapturer;
import com.twilio.video.ConnectOptions;
import com.twilio.video.LocalAudioTrack;
import com.twilio.video.LocalParticipant;
import com.twilio.video.LocalVideoTrack;
import com.twilio.video.RemoteAudioTrack;
import com.twilio.video.RemoteAudioTrackPublication;
import com.twilio.video.RemoteDataTrack;
import com.twilio.video.RemoteDataTrackPublication;
import com.twilio.video.RemoteParticipant;
import com.twilio.video.RemoteVideoTrack;
import com.twilio.video.RemoteVideoTrackPublication;
import com.twilio.video.Room;
import com.twilio.video.TwilioException;
import com.twilio.video.Video;
import com.twilio.video.VideoRenderer;
import com.twilio.video.VideoTrack;
import com.twilio.video.VideoView;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int CAMERA_MIC_PERMISSION_REQUEST_CODE = 1;

    private static final String ACCESS_TOKEN_SERVER = "http://kumail.org:5005/accessToken";
    private String accessToken;

    private static final String LOCAL_AUDIO_TRACK_NAME = "mic";
    private static final String LOCAL_VIDEO_TRACK_NAME = "camera";

    SharedPreferences prefs;
    SharedPreferences.Editor prefsEdit;

    Context context;
    FirebaseFirestore db;

    String identity;
    String friend;
    int myStatus;
    int friendStatus;
    String roomName;
    boolean inCall;

    Room room = null;
    private LocalParticipant localParticipant;

    private VideoView primaryVideoView;

    private TextView friendTextView;
    private CameraCapturerCompat cameraCapturerCompat;
    private LocalAudioTrack localAudioTrack;
    private LocalVideoTrack localVideoTrack;
    private FloatingActionButton availableFab;
    private FloatingActionButton busyFab;
    private FloatingActionButton offlineFab;
    private Button friendButton;
    private AlertDialog connectDialog;
    private AudioManager audioManager;
    private Drawable micIcon;
    private Drawable videoIcon;

    private int previousAudioMode;
    private boolean previousMicrophoneMute;
    private boolean disconnectedFromOnDestroy;
    private boolean isSpeakerPhoneEnabled = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = FirebaseFirestore.getInstance();
        context = getApplicationContext();

        MainActivity.this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        prefs = getSharedPreferences("user", MODE_PRIVATE);
        prefsEdit = prefs.edit();

        primaryVideoView = findViewById(R.id.primary_video_view);
        friendTextView = findViewById(R.id.friendText);

        availableFab = findViewById(R.id.available_fab);
        busyFab = findViewById(R.id.busy_fab);
        offlineFab = findViewById(R.id.offline_fab);
        friendButton = findViewById(R.id.friendStatus);

        micIcon = ContextCompat.getDrawable(context, R.drawable.ic_mic_off_black_24dp);
        videoIcon = ContextCompat.getDrawable(context, R.drawable.ic_videocam_white_24dp);
        friendButton.setCompoundDrawablesWithIntrinsicBounds(null, micIcon, null, videoIcon);

        if (!checkPermissionForCameraAndMicrophone()) {
            Log.d(TAG, "Didnt have permissions, asking for them");
            requestPermissionForCameraAndMicrophone();
        } else {
            Log.d(TAG, "Had permissions");
            createAudioAndVideoTracks();
        }

        initializeUI();

        // Enable changing the volume using up/down keys
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

        // Setting/abandoning audio focus during call
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(isSpeakerPhoneEnabled);

        identity = prefs.getString("username", "");
        myStatus = 2;
        friendStatus = 2;
        inCall = false;

        primaryVideoView.setVisibility(View.GONE);
        primaryVideoView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (inCall) {
                    exitCall();
                } else if (myStatus == 0 && friendStatus == 0) {
                    enterCall();
                }
            }
        });

        friend = prefs.getString("friend", "");
        if (friend.equals("")) {
            EditText roomEditText = new EditText(MainActivity.this);
            connectDialog = Dialog.createConnectDialog(roomEditText,
                    connectClickListener(roomEditText),
                    cancelConnectDialogClickListener(),
                    MainActivity.this);
            connectDialog.show();
        } else {
            initRoom();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (checkPermissionForCameraAndMicrophone()) {
            if (localVideoTrack == null) {
                localVideoTrack = localVideoTrack.create(this,
                        true,
                        cameraCapturerCompat.getVideoCapturer(),
                        LOCAL_VIDEO_TRACK_NAME);

                if (localParticipant != null) {
                    localParticipant.publishTrack(localVideoTrack);
                }
            }

            if (localAudioTrack == null) {
                localAudioTrack = LocalAudioTrack.create(this, true, LOCAL_AUDIO_TRACK_NAME);
            }
        }

        audioManager.setSpeakerphoneOn(isSpeakerPhoneEnabled);
    }

    @Override
    protected void onPause() {
        /*
         * Release the local video track before going in the background. This ensures that the
         * camera can be used by other applications while this app is in the background.
         */
        if (localVideoTrack != null) {
            /*
             * If this local video track is being shared in a Room, unpublish from room before
             * releasing the video track. Participants will be notified that the track has been
             * unpublished.
             */
            if (localParticipant != null) {
                localParticipant.unpublishTrack(localVideoTrack);
            }

            localVideoTrack.release();
            localVideoTrack = null;
        }

        if (localAudioTrack != null) {
            localAudioTrack.release();
            localAudioTrack = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (room != null && room.getState() != Room.State.DISCONNECTED) {
            room.disconnect();
            disconnectedFromOnDestroy = true;
        }

        if (localAudioTrack != null) {
            localAudioTrack.release();
            localAudioTrack = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }

        super.onDestroy();
    }

    private void connectToRoom() {
        setAccessToken(roomName, 5);
        Log.i(TAG, "connectToRoom local audio/video track: " + (localAudioTrack != null) + (localVideoTrack != null));
        if (accessToken != null && localAudioTrack != null & localVideoTrack != null) {
            ConnectOptions.Builder connectOptionsBuilder = new ConnectOptions.Builder(accessToken)
                    .roomName(roomName);
            connectOptionsBuilder.audioTracks(Collections.singletonList(localAudioTrack));
            connectOptionsBuilder.videoTracks(Collections.singletonList(localVideoTrack));
            room = Video.connect(this, connectOptionsBuilder.build(), roomListener());
        }
    }

    private void disconnectFromRoom() {
        if (room != null) {
            if (inCall) {
                exitCall();
            }
            room.disconnect();
        }
    }

    private void initializeUI() {
        availableFab.show();
        availableFab.setOnClickListener(clickStatusListener(0));
        busyFab.show();
        busyFab.setOnClickListener(clickStatusListener(1));
        offlineFab.show();
        offlineFab.setOnClickListener(clickStatusListener(2));
    }

    private void enterCall() {
        Log.d(TAG, "in enterCall, " + identity + " friend: " + friend);
        WriteBatch batch = db.batch();
        batch.update(db.collection("users").document(identity), "inCallWith", friend, "status", 1);
        batch.update(db.collection("users").document(friend), "inCallWith", identity, "status", 1);
        batch.commit().addOnSuccessListener((Void aVoid) -> {
            inCall = true;
            localAudioTrack.enable(true);
            micIcon = ContextCompat.getDrawable(context, R.drawable.ic_mic_white_24dp);
            friendButton.setCompoundDrawablesWithIntrinsicBounds(null, micIcon, null, videoIcon);
            changeFriendStatusUI(3);
        });
    }

    private void exitCall() {
        WriteBatch batch = db.batch();
        batch.update(db.collection("users").document(identity), "inCallWith", "", "status", 0);
        batch.update(db.collection("users").document(friend), "inCallWith", "", "status", 0);
        batch.commit().addOnSuccessListener((Void aVoid) -> {
            inCall = false;
            localAudioTrack.enable(false);
            micIcon = ContextCompat.getDrawable(context, R.drawable.ic_mic_off_black_24dp);
            friendButton.setCompoundDrawablesWithIntrinsicBounds(null, micIcon, null, videoIcon);
        });

    }

    private DialogInterface.OnClickListener connectClickListener(final EditText roomEditText) {
        return new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                friend = roomEditText.getText().toString();
                initRoom();
                prefsEdit.putString("friend", friend);
                prefsEdit.commit();
            }
        };
    }

    public void initRoom() {
        friendTextView.setTextColor(Color.WHITE);
        friendTextView.setText(friend.toUpperCase());
        db.collection("users").document(friend)
            .addSnapshotListener(new EventListener<DocumentSnapshot>() {
                @Override
                public void onEvent(@Nullable DocumentSnapshot documentSnapshot, @Nullable FirebaseFirestoreException e) {
                    if (e != null) {
                        Log.e(TAG, "error at snapshot", e);
                        return;
                    }

                    Map<String, Object> data = documentSnapshot.getData();
                    int newFriendStatus = ((Long) data.get("status")).intValue();
                    if (newFriendStatus == 2) {
                        disconnectFromRoom();
                    } else if (friendStatus == 2 && newFriendStatus != 2 && myStatus != 2) {
                        Log.d(TAG, "calling connecttoroom from friend snapshot");
                        if (room == null) {
                            connectToRoom();
                        }
                    }
                    friendStatus = newFriendStatus;
                    changeFriendStatusUI(friendStatus);
                }
            });
        db.collection("users").document(identity)
            .addSnapshotListener(new EventListener<DocumentSnapshot>() {
                @Override
                public void onEvent(@Nullable DocumentSnapshot documentSnapshot, @Nullable FirebaseFirestoreException e) {
                    if (e != null) {
                        Log.e(TAG, "error at snapshot", e);
                        return;
                    }

                    Map<String, Object> data = documentSnapshot.getData();
                    int newStatus = ((Long) data.get("status")).intValue();
                    if (newStatus == 2) {
                        disconnectFromRoom();
                    } else if (myStatus == 2 && newStatus != 2 && friendStatus != 2) {
                        Log.d(TAG, "calling connecttoroom from identity snapshot");
                        if (room == null) {
                            connectToRoom();
                        }
                    }
                    myStatus = newStatus;
                    changeStatusUI(myStatus);

                    String inCallWith = (String) data.get("inCallWith");
                    if (inCallWith.equals(friend) && !inCall) {
                        enterCall();
                    } else if (inCallWith.equals("") && inCall) {
                        exitCall();
                    }
                }
            });
        roomName = getRoomName(identity, friend);
    }

    private void changeStatusUI(int status) {
        availableFab.setImageResource(android.R.color.transparent);
        busyFab.setImageResource(android.R.color.transparent);
        offlineFab.setImageResource(android.R.color.transparent);
        if (status == 0) {
            availableFab.setImageResource(R.drawable.white_circle);
            videoIcon = ContextCompat.getDrawable(context, R.drawable.ic_videocam_white_24dp);
        } else if (status == 1) {
            busyFab.setImageResource(R.drawable.white_circle);
            videoIcon = ContextCompat.getDrawable(context, R.drawable.ic_videocam_white_24dp);
        } else {
            offlineFab.setImageResource(R.drawable.white_circle);
            videoIcon = ContextCompat.getDrawable(context, R.drawable.ic_videocam_off_black_24dp);
        }
        friendButton.setCompoundDrawablesWithIntrinsicBounds(null, micIcon, null, videoIcon);
    }

    private void changeFriendStatusUI(int status) {
        friendButton.setBackgroundColor(Constants.statusColors[status]);
    }

    private DialogInterface.OnClickListener cancelConnectDialogClickListener() {
        return new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                initializeUI();
                connectDialog.dismiss();
            }
        };
    }

    private View.OnClickListener clickStatusListener(int status) {
        final int temp = status;
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                db.collection("users").document(identity).get()
                    .addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
                        @Override
                        public void onSuccess(DocumentSnapshot documentSnapshot) {
                            Map<String, Object> data = documentSnapshot.getData();
                            if (data.get("inCallWith").equals("")) {
                                db.collection("users").document(identity)
                                    .update("status", temp);
                            }
                        }
                    });
            }
        };
    }

    private void addRemoteParticipant(RemoteParticipant remoteParticipant) {
        Log.i(TAG, "addRemoteParticipant remotevideotracks size: " + remoteParticipant.getRemoteVideoTracks().size());
        if (remoteParticipant.getRemoteVideoTracks().size() > 0) {
            RemoteVideoTrackPublication remoteVideoTrackPublication =
                    remoteParticipant.getRemoteVideoTracks().get(0);

            Log.i(TAG, "addRemoteParticipant is track subscribed: " + remoteVideoTrackPublication.isTrackSubscribed());
            if (remoteVideoTrackPublication.isTrackSubscribed()) {
                addRemoteParticipantVideo(remoteVideoTrackPublication.getRemoteVideoTrack());
            }
        }

        remoteParticipant.setListener(remoteParticipantListener());
    }

    private void removeRemoteParticipant(RemoteParticipant remoteParticipant) {
        Log.d(TAG, "removeRemoteParticipant");

        if (!remoteParticipant.getRemoteVideoTracks().isEmpty()) {
            RemoteVideoTrackPublication remoteVideoTrackPublication =
                    remoteParticipant.getRemoteVideoTracks().get(0);

            if (remoteVideoTrackPublication.isTrackSubscribed()) {
                removeParticipantVideo(remoteVideoTrackPublication.getRemoteVideoTrack());
            }
        }
        primaryVideoView.setVisibility(View.GONE);
    }

    private void addRemoteParticipantVideo(VideoTrack videoTrack) {
        Log.d(TAG, "addRemoteParticipantVideo");
        primaryVideoView.setMirror(false);
        videoTrack.addRenderer(primaryVideoView);
        primaryVideoView.setVisibility(View.VISIBLE);
    }

    private void removeParticipantVideo(VideoTrack videoTrack) {
        videoTrack.removeRenderer(primaryVideoView);
        primaryVideoView.setVisibility(View.GONE);
    }

    private String getRoomName(String person1, String person2) {
        if (person1.compareTo(person2) > 0) {
            return person1 + person2;
        } else {
            return person2 + person1;
        }
    }

    private void createAudioAndVideoTracks() {
        // Microphone
        localAudioTrack = LocalAudioTrack.create(this, true, LOCAL_AUDIO_TRACK_NAME);
        localAudioTrack.enable(false);

        // Share your camera
        cameraCapturerCompat = new CameraCapturerCompat(this, getAvailableCameraSource());
        localVideoTrack = LocalVideoTrack.create(this,
                true,
                cameraCapturerCompat.getVideoCapturer(),
                LOCAL_VIDEO_TRACK_NAME);
    }

    private void setAccessToken(String roomName, int retries) {
        try {
            accessToken = Ion.with(this)
                    .load(String.format("%s?identity=%s&room=%s", ACCESS_TOKEN_SERVER, identity, roomName))
                    .asString().get();
        } catch (Exception e) {
            Log.e(TAG, "setAccessToken" + e.getMessage());
            if (retries > 0) {
                setAccessToken(roomName, retries - 1);
            } else {
                Toast.makeText(this, "Error retrieving access token", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void configureAudio(boolean enable) {
        if (enable) {
            previousAudioMode = audioManager.getMode();
            // Request audio focus before making any device switch
            requestAudioFocus();
            /*
             * Use MODE_IN_COMMUNICATION as the default audio mode. It is required
             * to be in this mode when playout and/or recording starts for the best
             * possible VoIP performance. Some devices have difficulties with
             * speaker mode if this is not set.
             */
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            /*
             * Always disable microphone mute during a WebRTC call.
             */
            previousMicrophoneMute = audioManager.isMicrophoneMute();
            audioManager.setMicrophoneMute(false);
            audioManager.setSpeakerphoneOn(true);
        } else {
            audioManager.setMode(previousAudioMode);
            audioManager.abandonAudioFocus(null);
            audioManager.setMicrophoneMute(previousMicrophoneMute);
            audioManager.setSpeakerphoneOn(false);
        }
    }

    private void requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioManager.OnAudioFocusChangeListener listener = new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    return;
                }
            };

            AudioFocusRequest focusRequest =
                    new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(playbackAttributes)
                            .setAcceptsDelayedFocusGain(true)
                            .setOnAudioFocusChangeListener(listener)
                            .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    private CameraCapturer.CameraSource getAvailableCameraSource() {
        return (CameraCapturer.isSourceAvailable(CameraCapturer.CameraSource.FRONT_CAMERA)) ?
                (CameraCapturer.CameraSource.FRONT_CAMERA) :
                (CameraCapturer.CameraSource.BACK_CAMERA);
    }

    private boolean checkPermissionForCameraAndMicrophone() {
        int resultCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        int resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return resultCamera == PackageManager.PERMISSION_GRANTED &&
                resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionForCameraAndMicrophone() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                CAMERA_MIC_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == CAMERA_MIC_PERMISSION_REQUEST_CODE) {
            boolean cameraAndMicPermissionGranted = true;

            for (int grantResult : grantResults) {
                cameraAndMicPermissionGranted &= grantResult == PackageManager.PERMISSION_GRANTED;
            }

            if (cameraAndMicPermissionGranted) {
                createAudioAndVideoTracks();
            } else {
                Toast.makeText(this,
                        "Permissions are needed to run the app",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private Room.Listener roomListener() {
        return new Room.Listener() {
            @Override
            public void onConnected(Room room) {
                Log.d(TAG, "Connected to " + room.getName());
                localParticipant = room.getLocalParticipant();
                configureAudio(true);

                for (RemoteParticipant remoteParticipant : room.getRemoteParticipants()) {
                    addRemoteParticipant(remoteParticipant);
                    break;
                }
            }

            @Override
            public void onConnectFailure(Room room, TwilioException e) {
                Log.d(TAG, "Failed to connect " + e.getMessage());
                configureAudio(false);
                initializeUI();
            }

            @Override
            public void onDisconnected(Room room, TwilioException e) {
                Log.d(TAG, "in on disconnected");
                localParticipant = null;
                MainActivity.this.room = null;
                if (!disconnectedFromOnDestroy) {
                    configureAudio(false);
                    primaryVideoView.setVisibility(View.GONE);
                }
            }

            @Override
            public void onParticipantConnected(Room room, RemoteParticipant participant) {
                Log.d(TAG, "in onparticipantconnected");
                addRemoteParticipant(participant);
            }

            @Override
            public void onParticipantDisconnected(Room room, RemoteParticipant participant) {
                Log.d(TAG, "in onparticipantdisconnected");
                removeRemoteParticipant(participant);
            }

            @Override
            public void onRecordingStarted(Room room) {
                Log.d(TAG, "onRecordingStarted");
            }

            @Override
            public void onRecordingStopped(Room room) {
                Log.d(TAG, "onRecordingStopped");
            }

            @Override
            public void onReconnected(Room room) {
                Log.d(TAG, "onReconnected");
            }

            @Override
            public void onReconnecting(Room room, TwilioException exception) {
                Log.d(TAG, "onReconnecting");
            }
        };
    }

    private RemoteParticipant.Listener remoteParticipantListener() {
        return new RemoteParticipant.Listener() {
            @Override
            public void onAudioTrackPublished(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onAudioTrackUnpublished(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onAudioTrackSubscribed(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication, RemoteAudioTrack remoteAudioTrack) {

            }

            @Override
            public void onAudioTrackSubscriptionFailed(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication, TwilioException twilioException) {

            }

            @Override
            public void onAudioTrackUnsubscribed(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication, RemoteAudioTrack remoteAudioTrack) {

            }

            @Override
            public void onVideoTrackPublished(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication) {

            }

            @Override
            public void onVideoTrackUnpublished(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication) {

            }

            @Override
            public void onVideoTrackSubscribed(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication, RemoteVideoTrack remoteVideoTrack) {
                Log.i(TAG, "onVideoTrackSubscribed");
                addRemoteParticipantVideo(remoteVideoTrack);
            }

            @Override
            public void onVideoTrackSubscriptionFailed(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication, TwilioException twilioException) {
                Snackbar.make(availableFab,
                        String.format("Failed to subscribe to %s video track",
                                remoteParticipant.getIdentity()),
                        Snackbar.LENGTH_LONG)
                        .show();
            }

            @Override
            public void onVideoTrackUnsubscribed(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication, RemoteVideoTrack remoteVideoTrack) {
                Log.i(TAG, "onVideoTrackUnsubscribed");
                removeParticipantVideo(remoteVideoTrack);
            }

            @Override
            public void onDataTrackPublished(RemoteParticipant remoteParticipant, RemoteDataTrackPublication remoteDataTrackPublication) {

            }

            @Override
            public void onDataTrackUnpublished(RemoteParticipant remoteParticipant, RemoteDataTrackPublication remoteDataTrackPublication) {

            }

            @Override
            public void onDataTrackSubscribed(RemoteParticipant remoteParticipant, RemoteDataTrackPublication remoteDataTrackPublication, RemoteDataTrack remoteDataTrack) {

            }

            @Override
            public void onDataTrackSubscriptionFailed(RemoteParticipant remoteParticipant, RemoteDataTrackPublication remoteDataTrackPublication, TwilioException twilioException) {

            }

            @Override
            public void onDataTrackUnsubscribed(RemoteParticipant remoteParticipant, RemoteDataTrackPublication remoteDataTrackPublication, RemoteDataTrack remoteDataTrack) {

            }

            @Override
            public void onAudioTrackEnabled(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onAudioTrackDisabled(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onVideoTrackEnabled(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication) {
                primaryVideoView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onVideoTrackDisabled(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication) {
                primaryVideoView.setVisibility(View.GONE);
            }
        };
    }
}
