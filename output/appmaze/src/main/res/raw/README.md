# Placeholder audio files for AppMaze

This directory should contain the following WAV or OGG sound files:

1. **player_move.wav** (or .ogg)
   - Short tick sound for player movement
   - Duration: ~50-100ms
   - Frequency: 800-1000 Hz
   - Suggested: Simple sine wave beep

2. **wall_bump.wav** (or .ogg)
   - Thud sound for wall collision
   - Duration: ~100-150ms
   - Frequency: 300-400 Hz
   - Suggested: Low-frequency thump

3. **hint_reveal.wav** (or .ogg)
   - Chime sound for hint reveal
   - Duration: ~200-300ms
   - Frequency: 1200-1500 Hz
   - Suggested: Musical chime or bell sound

4. **game_complete.wav** (or .ogg)
   - Victory fanfare for game completion
   - Duration: ~1000-2000ms
   - Suggested: Triumphant musical phrase or fanfare

5. **button_click.wav** (or .ogg)
   - UI click sound for button presses
   - Duration: ~50-100ms
   - Frequency: 600-800 Hz
   - Suggested: Short click or pop sound

## How to Replace Placeholder Files

1. Create or download royalty-free audio files matching the descriptions above
2. Convert to WAV or OGG format (OGG is recommended for smaller file sizes)
3. Place files in this directory with the exact names listed above
4. No code changes needed — SoundManager will automatically load them

## Recommended Tools

- **Audacity** (free): Create or edit audio files
- **Freesound.org**: Download royalty-free sound effects
- **Zapsplat.com**: Free sound effects library
- **FFmpeg**: Convert audio formats

## Audio Format Recommendations

- **Format**: OGG Vorbis (better compression) or WAV (better compatibility)
- **Sample Rate**: 44.1 kHz or 48 kHz
- **Bit Depth**: 16-bit
- **Channels**: Mono (sufficient for SFX)
- **File Size**: Keep each file under 100 KB for optimal performance

## Testing

Once audio files are in place:
1. Run the app on an emulator or device
2. Play a game and perform actions (move, hit wall, use hint, complete)
3. Verify sounds play at appropriate times
4. Check Settings to toggle sound on/off
