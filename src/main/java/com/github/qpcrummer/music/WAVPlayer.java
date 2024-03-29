package com.github.qpcrummer.music;

import com.github.qpcrummer.Main;
import com.github.qpcrummer.beat.BeatManager;
import com.github.qpcrummer.gui.NewJukeboxGUI;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class WAVPlayer {

    private static Clip wavClip;
    private static final AtomicLong currentPosition = new AtomicLong();
    private static long songLength;
    private static FloatControl volume;
    private static boolean playing;
    private static boolean looping;
    private static int index1 = 0;
    private static final BeatManager beatManager = new BeatManager();
    private static int[] indexes;
    public static Path[] songPaths;
    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    /**
     * Run this if you change "songPaths"
     */
    public static void initialize() {
        indexes = new int[songPaths.length];
        for (int i = 0; i < songPaths.length; i++) {
            indexes[i] = i;
        }

        beatManager.initialize();
        initTimer();
    }

    /**
     * Plays the selected clip
     */
    public static void play(final int index) {
        System.gc();
        // Create AudioInputStream and Clip Objects
        index1 = indexes[index];
        // Disable GUI code in CLI mode
        if (!Main.cli) {
            updateSelectedValue();
            NewJukeboxGUI.title = "Playing " + getTitle(index1);
        }
        try (final AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(getPath(index1).toFile())) {
            wavClip = AudioSystem.getClip();
            wavClip.open(audioInputStream);
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            Main.logger.warning("AudioSystem failed to start!");
        }
        // Set Volume
        if (volume != null) {
            float value = getVolume();
            volume = (FloatControl) wavClip.getControl(FloatControl.Type.MASTER_GAIN);
            volume.setValue(value);
        } else {
            volume = (FloatControl) wavClip.getControl(FloatControl.Type.MASTER_GAIN);
        }

        if (!playing) {
            // Add song finished listener
            wavClip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    onSongEnd();
                }
            });

            // Set Playing True
            playing = true;
        }

        beatManager.readBeats(getPath(index1), index1);

        // Cache song length
        songLength = TimeUnit.MICROSECONDS.toSeconds(wavClip.getMicrosecondLength());

        // Start the Music!!!
        wavClip.start();

        // Start Beat Tracking
        beatManager.startBeatTracking();
    }

    /**
     * Resumes the selected audio clip at the time when it stopped
     */
    public static boolean resume() {
        if (playing) {
            return false;
        }

        if (wavClip == null) {
            reset();
            play(getCurrentSong());
        } else {
            wavClip.setMicrosecondPosition(currentPosition.get());
            wavClip.start();
            playing = true;
        }
        return true;
    }

    /**
     * Pauses the selected audio clip
     */
    public static boolean pause() {
        if (!playing) {
            return false;
        }

        playing = false;
        currentPosition.set(wavClip.getMicrosecondPosition());
        wavClip.stop();
        return true;
    }

    /**
     * Cancels and resets the audio clip
     */
    public static void reset() {
        if (playing) {
            wavClip.stop();
            wavClip.close();
        }
        if (!Main.cli) {
            NewJukeboxGUI.cachedFormattedSongLength = null;
        }
        playing = false;
        currentPosition.set(0);
        songLength = 0L;
        looping = false;
        beatManager.resetBeats();
    }

    /**
     * Completely removes all threads and data related to the Jukebox
     */
    public static void shutDown() {
        reset();
        index1 = 0;
        beatManager.stopThread();
    }

    /**
     * Skips the song and moves to the next
     */
    public static void skip() {
        reset();
        play(getNextSong());
        if (!Main.cli) {
            NewJukeboxGUI.cachedFormattedSongLength = null;
        }
    }

    /**
     * Restarts a Clip from the beginning
     */
    public static void rewind() {
        pause();
        currentPosition.set(0);
        resume();
    }

    /**
     * Mixes up the order of Songs
     */
    public static void shuffle() {
        reset();
        final int length = indexes.length;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            int randomIndexToSwap = random.nextInt(length);
            int temp = indexes[randomIndexToSwap];
            indexes[randomIndexToSwap] = indexes[i];
            indexes[i] = temp;
        }
        play(0);
    }

    /**
     * Starts the currentPosition timer
     */
    private static void initTimer() {
        timer.scheduleAtFixedRate(() -> {
            if (isPlaying()) {
                currentPosition.getAndAdd(10000);
            }
        }, 0, 10, TimeUnit.MILLISECONDS);
    }

    /**
     * Index when clicked on in the JLIst
     * @param index index of the song clicked on
     */
    public static void songOverride(final int index) {
        reset();
        play(index);
    }

    /**
     * Executes when a song has completed
     */
    private static void onSongEnd() {
        if (wavClip.getFrameLength() <= wavClip.getLongFramePosition()) {
            if (looping) {
                wavClip.setFramePosition(0);
                resume();
            } else {
                skip();
            }
        }
    }

    /**
     * Updates the selected index of the ImList
     */
    private static void updateSelectedValue() {
        NewJukeboxGUI.setSelectedSong(getCurrentSong());
    }

    // Info Methods

    /**
     * Enables looping WAV files
     * @param setLooping boolean toggle
     */
    public static void setLooping(final boolean setLooping) {
        looping = setLooping;
    }

    /**
     * Toggles the looping boolean
     * @return looping boolean
     */
    public static boolean toggleLooping() {
        looping = !looping;
        return looping;
    }

    /**
     * Checks if a WAV file is playing
     * @return if WAV is playing
     */
    public static boolean isPlaying() {
        return playing;
    }

    /**
     * Gets the current position, but it may be a few microseconds behind
     * @return current position as a long
     */
    public static long getCurrentPositionLessAccurate() {
        return  currentPosition.get();
    }

    /**
     * Returns song length in seconds
     * @return song length in seconds as long value
     */
    public static long getSongLength() {
        return songLength;
    }

    /**
     * Grabs the next song's index. If it is at the end, it goes to the beginning
     * @return Next song's index to play
     */
    public static int getNextSong() {
        if (index1 >= indexes.length - 1) {
            return 0;
        } else {
            index1++;
            return index1;
        }
    }

    /**
     * Gets the current song playing
     * @return Returns the current song's index
     */
    public static int getCurrentSong() {
        return index1;
    }

    /**
     * Calculates the volume based on slider
     * @param sliderValue ImGUI Slider value
     */
    public static void calcVolume(final double sliderValue) {
        if (volume == null) {
            Main.logger.warning("Volume cannot be set if a song is not loaded");
            return;
        }

        double newVolume;
        if (sliderValue == 0) {
            newVolume = -80;
        } else {
            newVolume = 30 * Math.log10(sliderValue) - 60;
        }
        volume.setValue((float) newVolume);
    }

    public static float getVolume() {
        return volume.getValue();
    }

    /**
     * Gets the name of the Song at the specific index
     * @param index index in song List
     * @return Name and Author as a String
     */
    public static String getTitle(int index) {
        return NewJukeboxGUI.titleList[index];
    }

    /**
     * Gets the path of the Song at the specific index
     * @param index index in song List
     * @return Path
     */
    public static Path getPath(int index) {
        return songPaths[index];
    }
}
