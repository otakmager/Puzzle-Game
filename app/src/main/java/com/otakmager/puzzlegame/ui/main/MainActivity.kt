package com.otakmager.puzzlegame.ui.main

import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.widget.ImageButton
import com.otakmager.puzzlegame.R
import com.otakmager.puzzlegame.data.game.PuzzleImage
import com.otakmager.puzzlegame.data.state.StatePair
import com.otakmager.puzzlegame.databinding.ActivityMainBinding
import com.otakmager.puzzlegame.utils.game.ShuffleRunnable
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    /***************
     * Shared Preferences to save data in local *
     **************/
    private lateinit var sp: SharedPreferences

    /***************
     * Dimensions *
     **************/
    private var tileDimen: Int = 0
    private var puzzleDimen: Int = 0

    /*********
     * State *
     *********/
    private lateinit var goalPuzzleState: ArrayList<Int>
    private lateinit var puzzleState: ArrayList<Int>
    private var blankTilePos: Int = BLANK_TILE_MARKER
    private var isPuzzleGridFrozen: Boolean = false
    private var isGameInSession: Boolean = false

    /**********
     * Images *
     **********/
    private lateinit var puzzleImage: Bitmap
    private lateinit var imageChunks: ArrayList<Bitmap>
    private lateinit var blankImageChunks: ArrayList<Bitmap>
    private lateinit var tileImages: ArrayList<ImageButton>
    private lateinit var puzzleImageChoices: Array<PuzzleImage>
    private var puzzleImageIndex: Int = 0
    private var indexOfCustom: Int = 0
    private var isGalleryImageChosen: Boolean = false

    /********************************
     * Shuffling-Related Properties *
     ********************************/
    private lateinit var shuffleRunnable: ShuffleRunnable
    private lateinit var shuffleScheduler: ScheduledExecutorService
    private lateinit var shuffleHandler: Handler

    /****************************
     * Timer-Related Properties *
     ****************************/
    private lateinit var timerHandler: Handler
    private var isTimerRunning: Boolean = false

    /**************
     * Statistics *
     **************/
    private var numMoves: Long = 0
    private var fewestMoves: Long = DEFAULT_FEWEST_MOVES
    private var timeTaken: Long = 0
    private var fastestTime: Long = DEFAULT_FASTEST_TIME

    /******************************
     * Solving-Related Properties *
     ******************************/
    private var puzzleSolution: Stack<StatePair>? = null
    private var numMovesSolution: Int = 0
    private lateinit var solveHandler: Handler
    private lateinit var solveDisplayHandler: Handler
    private var isSolutionDisplay: Boolean = false
    private var isSolutionPlay: Boolean = false
    private var isSolutionSkip: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    companion object {
        /**
         * Number of columns in the 8-puzzle grid.
         */
        private const val NUM_COLUMNS = 3

        /**
         * Number of tiles in the 8-puzzle grid.
         */
        private const val NUM_TILES = NUM_COLUMNS * NUM_COLUMNS

        /**
         * Thickness of the tile border (in pixels).
         */
        private const val BORDER_OFFSET = 6

        /**
         * Indicator that the tile is blank.
         */
        private const val BLANK_TILE_MARKER = NUM_TILES - 1

        /**
         * Default value for the fewest number of moves and fastes time,
         * its initial value before the user starts playing their first game.
         */
        private const val DEFAULT_FEWEST_MOVES = Long.MAX_VALUE
        private const val DEFAULT_FASTEST_TIME = Long.MAX_VALUE
    }
}