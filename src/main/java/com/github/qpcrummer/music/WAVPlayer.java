package com.github.qpcrummer.music;

import com.drew.lang.annotations.NotNull;
import com.github.qpcrummer.beat.BeatManager;
import com.github.qpcrummer.directories.Song;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.event.ListSelectionListener;
import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class WAVPlayer {
    private Clip wavClip;
    private long currentPosition;
    private FloatControl volume;
    private boolean playing;
    private boolean looping;
    private final JProgressBar progressBar;
    private final List<Song> playList;
    private Song currentSong;
    private int index;
    private final JList<Song> songJList;
    private final ListSelectionListener listener;
    private final JFrame parent;
    private final BeatManager beatManager;
    private int progressBarIndex;
    private String cachedFinalTimeStamp;
    private int songLengthSeconds;
    public WAVPlayer(@NotNull JProgressBar bar, List<Song> playList, @NotNull JList<Song> songJList, ListSelectionListener songJListListener, JFrame parent) {
        this.progressBar = bar;
        this.playList = playList;
        this.songJList = songJList;
        this.listener = songJListListener;
        this.parent = parent;
        // Beats
        this.beatManager = new BeatManager(this);

        // ProgressBarUpdater
        this.beatManager.getBeatExecutor().scheduleAtFixedRate(() -> {
            if (isPlaying()) {
                progressBar.setValue((progressBarIndex * 100)/ this.songLengthSeconds);
                progressBar.setString(formatTime(progressBarIndex) + "/" + this.cachedFinalTimeStamp);
                progressBarIndex++;
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Plays the selected clip
     */
    public void play(Song song) {
        // Create AudioInputStream and Clip Objects
        this.currentSong = song;
        this.updateSelectedValue();
        this.parent.setTitle("Playing " + song.title);
        String wavPath = String.valueOf(song.path);
        try {
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new File(wavPath).getAbsoluteFile());
            this.wavClip = AudioSystem.getClip();
            this.wavClip.open(audioInputStream);
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            e.printStackTrace();
        }
        // Set Volume
        this.volume = (FloatControl) this.wavClip.getControl(FloatControl.Type.MASTER_GAIN);

        if (!playing) {
            // Add song finished listener
            this.wavClip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    this.onSongEnd();
                }
            });

            // Set data for ProgressBar
            this.songLengthSeconds = (int) TimeUnit.MICROSECONDS.toSeconds(wavClip.getMicrosecondLength());
            this.cachedFinalTimeStamp = formatTime(songLengthSeconds);

            // Set Playing True
            this.playing = true;
        }

        this.beatManager.readBeats(song);

        // Start the Music!!!
        this.wavClip.start();

        // Start Beat Tracking
        this.beatManager.startBeatTracking();
    }

    /**
     * Resumes the selected audio clip at the time when it stopped
     */
    public void resume() {
        if (this.wavClip == null) {
            play(getCurrentSong());
            return;
        }
        this.wavClip.setMicrosecondPosition(this.currentPosition);
        this.wavClip.start();
        this.playing = true;
    }

    /**
     * Pauses the selected audio clip
     */
    public void pause() {
        this.currentPosition = wavClip.getMicrosecondPosition();
        this.wavClip.stop();
        this.playing = false;
    }

    /**
     * Cancels and resets the audio clip
     */
    public void reset() {
        if (playing) {
            this.wavClip.stop();
            this.wavClip.close();
        }
        this.wavClip = null;
        this.playing = false;
        this.currentPosition = 0L;
        this.progressBarIndex = 0;
    }

    /**
     * Completely removes all threads and data related to the Jukebox
     */
    public void shutDown() {
        reset();
        this.beatManager.stopThread();
    }

    /**
     * Skips the song and moves to the next
     */
    public void skip() {
        this.reset();
        this.play(this.getNextSong());
    }

    /**
     * Restarts a Clip from the beginning
     */
    public void rewind() {
        this.pause();
        this.currentPosition = 0L;
        this.progressBarIndex = 0;
        this.resume();
    }

    /**
     * Mixes up the order of Songs
     */
    public void shuffle() {
        this.reset();
        this.index = 0;
        Collections.shuffle(this.playList);
        this.play(this.getCurrentSong());
    }

    /**
     * Injected Song when clicked on in the JLIst
     * @param song Song clicked on
     */
    public void songOverride(Song song) {
        this.reset();
        this.index = this.playList.indexOf(song);
        this.play(song);
    }

    /**
     * Correctly format the progress bar
     * @param seconds Current position of the song in seconds
     * @return The formatted time
     */
    private String formatTime(int seconds) {
        final Calendar time_format = new Calendar.Builder().build();
        time_format.set(Calendar.SECOND, seconds);

        String second;
        if (time_format.get(Calendar.SECOND) <= 9) {
            second = 0 + String.valueOf(time_format.get(Calendar.SECOND));
        } else {
            second = String.valueOf(time_format.get(Calendar.SECOND));
        }

        String min;
        if (time_format.get(Calendar.MINUTE) <= 9) {
            min = 0 + String.valueOf(time_format.get(Calendar.MINUTE));
        } else {
            min = String.valueOf(time_format.get(Calendar.MINUTE));
        }
        return min + ":" + second;
    }

    /**
     * Executes when a song has completed
     */
    private void onSongEnd() {
        if (this.wavClip.getFrameLength() <= this.wavClip.getLongFramePosition()) {
            if (this.looping) {
                this.wavClip.setFramePosition(0);
                this.beatManager.resetBeats();
                this.progressBarIndex = 0;
                this.resume();
            } else {
                this.skip();
            }
        }
    }

    /**
     * Updates the selected index of the JList
     */
    private void updateSelectedValue() {
        this.songJList.removeListSelectionListener(this.listener);
        this.songJList.setSelectedValue(this.getCurrentSong(), true);
        this.songJList.addListSelectionListener(this.listener);
    }

    // Info Methods

    /**
     * Enables looping WAV files
     * @param setLooping boolean toggle
     */
    public void setLooping(boolean setLooping) {
        this.looping = setLooping;
    }

    /**
     * Checks if a WAV file is playing
     * @return if WAV is playing
     */
    public boolean isPlaying() {
        return this.playing;
    }

    /**
     * Grabs the next song in the List. If it is at the end, it goes to the beginning
     * @return Next song to play
     */
    public Song getNextSong() {
        this.index++;
        if (this.index >= this.playList.size()) {
            this.index = 0;
        }
        return this.playList.get(this.index);
    }

    /**
     * Gets the current song playing
     * @return Returns the current song
     */
    public Song getCurrentSong() {
        if (currentSong == null) {
            currentSong = this.playList.get(this.index);
        }
        return this.currentSong;
    }

    /**
     * Gets the current Clip playing
     * @return Returns current Clip
     */
    public Clip getWavClip() {
        return this.wavClip;
    }

    /**
     * Calculates the volume based on slider
     * @param sliderValue JSlider value
     */
    public void calcVolume(double sliderValue) {
        double new_volume;
        if (sliderValue == 0) {
            new_volume = -80;
        } else {
            new_volume = 30 * Math.log10(sliderValue) - 60;
        }
        this.volume.setValue((float) new_volume);
    }
}
