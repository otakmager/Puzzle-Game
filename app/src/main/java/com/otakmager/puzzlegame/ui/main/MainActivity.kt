package com.otakmager.puzzlegame.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.otakmager.puzzlegame.R
import com.otakmager.puzzlegame.data.game.FlingDirection
import com.otakmager.puzzlegame.data.game.Key
import com.otakmager.puzzlegame.data.game.PuzzleImage
import com.otakmager.puzzlegame.data.state.SolveStatus
import com.otakmager.puzzlegame.data.state.StatePair
import com.otakmager.puzzlegame.databinding.ActivityMainBinding
import com.otakmager.puzzlegame.ui.custom.GridViewGesture
import com.otakmager.puzzlegame.utils.event.OnFlingListener
import com.otakmager.puzzlegame.utils.game.*
import java.util.*
import java.util.Collections.swap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    /***************************
     * View-Related Properties *
     ***************************/
    private lateinit var clRoot: ConstraintLayout
    private lateinit var gvgPuzzle: GridViewGesture
    private lateinit var btnUpload: Button
    private lateinit var btnShuffle: Button
    private lateinit var pbShuffle: ProgressBar
    private lateinit var tvMoveNumber: TextView
    private lateinit var tvFewestMoves: TextView
    private lateinit var tvTimeTaken: TextView
    private lateinit var tvFastestTime: TextView
    private lateinit var spnPuzzle: Spinner
    private lateinit var tvTitle: TextView
    private lateinit var tvSuccess: TextView
    private lateinit var tvTrivia: TextView

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
    private lateinit var galleryLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initComponents()
        initSharedPreferences()
        initHandlers()
        initStateAndTileImages()
        initPuzzle()
        initGalleryLauncher()
    }

    override fun onResume() {
        super.onResume()

        if (isGalleryImageChosen) {
            isGalleryImageChosen = false
        } else {
            spnPuzzle.setSelection(puzzleImageIndex)
        }
    }

    private fun initComponents() {
        /* Initialize the root layout and the grid view. */
        clRoot = findViewById(R.id.cl_root)
        gvgPuzzle = findViewById(R.id.gvg_puzzle)

        /* Initialize the buttons. */
        btnShuffle = findViewById(R.id.btn_shuffle)
        setBtnShuffleAction()

        btnUpload = findViewById(R.id.btn_upload)
        setBtnUploadAction()

        /* Initialize the progress bar. */
        pbShuffle = findViewById(R.id.pb_shuffle)

        /* Initialize the text views. */
        tvMoveNumber = findViewById(R.id.tv_move_number)
        tvFewestMoves = findViewById(R.id.tv_fewest_moves)
        tvTimeTaken = findViewById(R.id.tv_time_taken)
        tvFastestTime = findViewById(R.id.tv_fastest_time)

        tvTitle = findViewById(R.id.tv_title)
        tvSuccess = findViewById(R.id.tv_success)
        tvSuccess.setOnClickListener {
            tvSuccess.visibility = View.GONE
        }

        tvTrivia = findViewById(R.id.tv_trivia)

        /* Initialize the puzzle spinner and its adapter. */
        spnPuzzle = findViewById(R.id.spn_puzzle)
        spnPuzzle.adapter = SpinnerAdapter(
            this,
            R.layout.spn_puzzle_item,
            resources.getStringArray(R.array.puzzle_images)
        )

        /* Initialize the selection of puzzle images. */
        puzzleImageChoices = PuzzleImage.values()
        indexOfCustom = puzzleImageChoices.lastIndex
    }


    private fun initSharedPreferences() {
        sp = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)

        /*
         * Retrieve data on the fewest moves and fastest time.
         * These data are stored as strings to prevent problems related to integer overflow.
         */
        fewestMoves =
            sp.getString(Key.KEY_FEWEST_MOVES.name, DEFAULT_FEWEST_MOVES.toString())?.toLong()
                ?: DEFAULT_FEWEST_MOVES
        fastestTime =
            sp.getString(Key.KEY_FASTEST_TIME.name, DEFAULT_FASTEST_TIME.toString())?.toLong()
                ?: DEFAULT_FASTEST_TIME
        displayStats()
    }

    /**
     * Initializes the handlers related to shuffling the tiles, triggering the game timer, and
     * displaying the solution.
     */
    private fun initHandlers() {
        /* Initialize thread pool executor and handler related to the shuffling animation. */
        shuffleScheduler = Executors.newScheduledThreadPool(NUM_TILES)
        shuffleHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                super.handleMessage(message)

                /* Animate UI elements while shuffling. */
                showTileAt(message.data.getInt(Key.KEY_TILE_POSITION.name))
                pbShuffle.progress = message.data.getInt(Key.KEY_PROGRESS.name)
                updateComponents()
            }
        }

        /* Initialize the handler related to the timer. */
        timerHandler = Handler(Looper.getMainLooper())

        /* Initialize the handler related to the puzzle solution. */
        solveHandler = Handler(Looper.getMainLooper())
        solveDisplayHandler = Handler(Looper.getMainLooper())
    }

    /**
     * Initializes the puzzle state and the images of the tiles in the grid.
     */
    private fun initStateAndTileImages() {
        goalPuzzleState = ArrayList(NUM_TILES)
        puzzleState = ArrayList(NUM_TILES)
        tileImages = ArrayList(NUM_TILES)

        for (tile in 0 until NUM_TILES) {
            goalPuzzleState.add(tile)
            puzzleState.add(tile)

            /*
             * Initialize at activity creation to prevent potentially expensive creation
             * of image button objects. Only the background resources of these image buttons
             * are changed when the tiles are moved.
             */
            tileImages.add(ImageButton(this))
        }
    }

    /**
     * Resets the puzzle state back to the original state (that is, the goal state).
     */
    private fun resetState() {
        puzzleState = goalPuzzleState.toMutableList() as ArrayList<Int>
        blankTilePos = BLANK_TILE_MARKER
    }

    /**
     * Initializes the dynamic components and listeners related to the puzzle grid.
     */
    private fun initPuzzle() {
        setTouchSlopThreshold()
        setOnFlingListener()
        setDimensions()
    }

    /**
     * Initializes the activity result launcher related to choosing an image from the Gallery.
     */
    private fun initGalleryLauncher() {
        galleryLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == Activity.RESULT_OK) {
                    loadPuzzle(result.data?.data)
                }
            }
    }

    /*************************************************
     * Methods Related to Button and Spinner Actions *
     *************************************************/

    /**
     * Sets the click event listener for the button for shuffling the tiles (if the game is not yet
     * in session) or displaying the solution (if the game is already in session).
     */
    private fun setBtnShuffleAction() {
        btnShuffle.setOnClickListener {
            if (isSolutionDisplay) {
                controlSolutionDisplay()
            } else if (!isGameInSession) {
                shuffle()
            } else {
                solve()
            }
        }
    }

    /**
     * Sets the click event listener for the button for selecting a custom puzzle image from the
     * Gallery (if the game is not yet in session) or skipping the solution (if the solution
     * walkthrough is currently being played).
     */
    private fun setBtnUploadAction() {
        btnUpload.setOnClickListener {
            if (isSolutionDisplay) {
                skipSolution()
            } else {
                if (spnPuzzle.selectedItemPosition != indexOfCustom) {
                    spnPuzzle.setSelection(indexOfCustom)
                } else {
                    uploadPuzzleImage()
                }
            }
        }
    }

    /**
     * Sets the item selection event listener for the spinner.
     */
    private fun setSpnPuzzleAction() {
        spnPuzzle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            /**
             * Callback method to be invoked when an item in this view has been
             * selected. This callback is invoked only when the newly selected
             * position is different from the previously selected position or if
             * there was no selected item.
             *
             * Implementers can call <code>getItemAtPosition(position)</code> if they need
             * to access the data associated with the selected item.
             *
             * @param parent <code>AdapterView</code> where the selection happened
             * @param view View within the <code>AdapterView</code> that was clicked
             * @param position Position of the view in the adapter
             * @param id Row ID of the item that is selected
             */
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position != indexOfCustom) {
                    loadPuzzle(position)
                } else {
                    uploadPuzzleImage()
                }
            }

            /**
             * Callback method to be invoked when the selection disappears from this
             * view. The selection can disappear for instance when touch is activated
             * or when the adapter becomes empty.
             *
             * @param parent The <code>AdapterView</code> that now contains no selected item.
             */
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /*****************************************
     * Methods Related to Statistics Display *
     *****************************************/

    /**
     * Displays the game statistics (namely the fewest number of moves and the fastest time taken
     * to solve the 8-puzzle).
     */
    private fun displayStats() {
        displayFewestMoves()
        displayFastestTime()
    }

    /**
     * Displays the fewest number of moves taken to solve the 8-puzzle.
     */
    private fun displayFewestMoves() {
        tvFewestMoves.text = if (fewestMoves == DEFAULT_FEWEST_MOVES) {
            getString(R.string.default_move_count)
        } else {
            fewestMoves.toString()
        }
    }

    /**
     * Displays the fewest time taken to solve the 8-puzzle.
     */
    private fun displayFastestTime() {
        tvFastestTime.text = if (fastestTime == DEFAULT_FASTEST_TIME) {
            getString(R.string.default_timer)
        } else {
            TimeUtil.displayTime(fastestTime)
        }
    }

    /**
     * Removes the statistics displayed (replacing them with hyphen placeholders) when the solution
     * walkthrough is played.
     */
    private fun blankDisplayedStats() {
        /* Remove the statistics for the number of moves, and display them. */
        numMoves = 0
        tvMoveNumber.text = getString(R.string.default_move_count)

        /* Remove the statistics for the time taken, and display them. */
        timeTaken = 0
        tvTimeTaken.text = getString(R.string.default_timer)
    }

    /**
     * Resets the statistics displayed (setting their values to 0) at the start of a game (that is,
     * immediately after the shuffling animation finishes).
     */
    private fun resetDisplayedStats() {
        /* Reset the statistics for the number of moves, and display them. */
        numMoves = 0
        tvMoveNumber.text = numMoves.toString()

        /* Reset the statistics for the time taken, and display them. */
        timeTaken = 0
        tvTimeTaken.text = TimeUtil.displayTime(timeTaken)
    }

    /**********************************
     * Methods Related to Puzzle Grid *
     **********************************/

    /**
     * Sets the distance in pixels a touch can wander before it is registered as a fling gesture.
     */
    private fun setTouchSlopThreshold() {
        gvgPuzzle.setTouchSlopThreshold(ViewConfiguration.get(this).scaledTouchSlop)
    }

    /**
     * Sets the listener for responding to detected fling gestures.
     */
    private fun setOnFlingListener() {
        gvgPuzzle.setFlingListener(object : OnFlingListener {
            override fun onFling(direction: FlingDirection, position: Int) {
                moveTile(direction, position)
            }
        })
    }

    /**
     * Sets the dimensions of the puzzle grid and its individual tiles.
     */
    private fun setDimensions() {
        gvgPuzzle.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                gvgPuzzle.viewTreeObserver.removeOnGlobalLayoutListener(this)

                /* Calculate the side length of each square tile. */
                puzzleDimen = gvgPuzzle.measuredWidth
                tileDimen = puzzleDimen / NUM_COLUMNS

                /*
                 * This method uses the results of the calculation of the side length of each
                 * square tile and the side length of the entire grid.
                 *
                 * Therefore, it is imperative that this method is called only after the said
                 * dimensions have been calculated.
                 */
                setSpnPuzzleAction()

                initPuzzleImage()
                initChunks()
                displayPuzzle()
            }
        })
    }

    /********************************************
     * Methods Related to Puzzle Image and Grid *
     ********************************************/

    /**
     * Sets the puzzle image to the most recently selected image (persists even when the app is closed)
     * and resizes (crops) it to fit the dimensions of the puzzle grid.
     */
    private fun initPuzzleImage() {
        /* Retrieve the most recently displayed puzzle image. */
        puzzleImageIndex = sp.getInt(Key.KEY_PUZZLE_IMAGE.name, 0)
        spnPuzzle.setSelection(puzzleImageIndex)

        puzzleImage = ImageUtil.resizeToSquareBitmap(
            ImageUtil.drawableToBitmap(
                this@MainActivity,
                puzzleImageChoices[puzzleImageIndex].drawableId
            ),
            puzzleDimen,
            puzzleDimen
        )
    }

    /**
     * Sets the image chunks displayed on the individual puzzle tiles.
     */
    private fun initChunks() {
        /* Store copies of the tiles, alongside versions with dark filter applied (blank tiles). */
        imageChunks =
            ImageUtil.splitBitmap(
                puzzleImage,
                tileDimen - BORDER_OFFSET,
                NUM_TILES,
                NUM_COLUMNS
            ).first
        blankImageChunks =
            ImageUtil.splitBitmap(
                puzzleImage,
                tileDimen - BORDER_OFFSET,
                NUM_TILES,
                NUM_COLUMNS
            ).second
    }

    /**
     * Displays the puzzle grid, with the correct tile images (8 tiles with no filter applied
     * and 1 tile with a dark color filter applied to designate it as the blank tile).
     */
    private fun displayPuzzle() {
        /*
         * Once this loop finished executing, there should be 9 distinct image chunks:
         * 8 tiles with no filter applied and 1 tile with dark filter applied (blank tile).
         */
        for ((position, tile) in puzzleState.withIndex()) {
            /* Properly reflect the blank tile depending on the puzzle state. */
            if (position == blankTilePos) {
                tileImages[blankTilePos].setImageBitmap(blankImageChunks[blankTilePos])
            } else {
                tileImages[position].setImageBitmap(imageChunks[tile])
            }
        }

        /*
         * Set (or reset) the adapter of the grid view.
         * This data should persist even after the app is closed so that the same image is displayed
         * on the next startup.
         */
        gvgPuzzle.adapter = TileAdapter(tileImages, tileDimen, tileDimen)
    }

    /**
     * Display the puzzle grid with all 9 tiles having a dark color filter applied.
     *
     * This method is invoked at the start of the shuffling animation.
     */
    private fun displayBlankPuzzle() {
        /*
         * Once this loop finished executing, there should be 9 image chunks, all of which have
         * dark filter applied. However, among these chunks, there should be be a tile that appears
         * twice depending on which tile is the actual blank tile.
         */
        for ((position, tile) in puzzleState.withIndex()) {
            /* Properly reflect the blank tile depending on the puzzle state. */
            if (position == blankTilePos) {
                tileImages[blankTilePos].setImageBitmap(blankImageChunks[blankTilePos])
            } else {
                tileImages[position].setImageBitmap(blankImageChunks[tile])
            }
        }

        /* Set (or reset) the adapter of the grid view. */
        gvgPuzzle.adapter = TileAdapter(tileImages, tileDimen, tileDimen)
    }

    /**
     * Loads and displays the puzzle grid, with the correct tile images (8 tiles with no filter applied
     * and 1 tile with a dark color filter applied to designate it as the blank tile).
     */
    private fun loadPuzzle(position: Int) {
        /*
         * Handle the case when the spinner is clicked while the success message is still
         * on display.
         */
        tvSuccess.visibility = View.GONE
        resetState()

        updatePuzzleImage(position)
        initChunks()
        displayPuzzle()
    }

    /**
     * Updates the puzzle image displayed on the grid when a new image is chosen via the spinner.
     *
     * @param position Position of the selected puzzle image in the spinner adapter.
     */
    private fun updatePuzzleImage(position: Int) {
        puzzleImageIndex = position

        puzzleImage = ImageUtil.resizeToSquareBitmap(
            ImageUtil.drawableToBitmap(
                this@MainActivity, puzzleImageChoices[puzzleImageIndex].drawableId
            ),
            puzzleDimen,
            puzzleDimen
        )

        /* Set this image as the most recently selected puzzle image. */
        with(sp.edit()) {
            putInt(Key.KEY_PUZZLE_IMAGE.name, puzzleImageIndex)
            commit()
        }
    }

    /***********************************
     * Methods Related to Moving Tiles *
     ***********************************/

    /**
     * Moves the specified tile in the given direction of the user's fling gesture.
     *
     * @param direction Direction of the user's fling gesture.
     * @param position Position of the tile to be moved (zero-based, following row-major order).
     */
    private fun moveTile(direction: FlingDirection, position: Int) {
        /* Use a flag to keep track of whether the success message can be removed. */
        var flag = false

        /* Move a tile only when the puzzle grid is clickable. */
        if (!isPuzzleGridFrozen) {
            if (MoveUtil.canMoveTile(direction, position, blankTilePos, NUM_COLUMNS)) {
                /* Swap the flung tile and the blank tile to create an effect akin to tile sliding. */
                swap(puzzleState, position, blankTilePos)
                blankTilePos = position

                /* Update the grid and the statistics. */
                displayPuzzle()
                flag = updateGameStatus()

                /* Launch the timer after the first move. */
                if (numMoves == 1L) {
                    launchTimer()
                }
            }

            /*
             * Remove the success message (if it is displayed). This scenario is triggered when
             * the user moves a tile while the success message is still on display, right
             * after completing a game.
             */
            if (!flag) {
                tvSuccess.visibility = View.GONE
            }
        }
    }

    /**
     * Updates the game status depending on whether user solves the puzzle
     * or opts to play the solution walkthrough instead.
     */
    private fun updateGameStatus(): Boolean {
        if (isGameInSession) {
            trackMove()

            /* Check if the puzzle has been solved. */
            if (SolveUtil.isSolved(puzzleState, goalPuzzleState)) {
                /*
                 * Decrement to counter the effect of the last post-increment operation
                 * before the timer was stopped.
                 */
                timeTaken--

                if (numMoves < fewestMoves && timeTaken < fastestTime) {
                    endGame(SolveStatus.FEWEST_AND_FASTEST)
                } else if (numMoves < fewestMoves) {
                    endGame(SolveStatus.FEWEST_MOVES)
                } else if (timeTaken < fastestTime) {
                    endGame(SolveStatus.FASTEST_TIME)
                } else {
                    endGame(SolveStatus.USER_SOLVED)
                }

                return true
            }
        }

        return false
    }

    /**
     * Updates and displays the number of movies every time a user moves a tile.
     */
    private fun trackMove() {
        numMoves++
        tvMoveNumber.text = numMoves.toString()
    }

    /**
     * Launches and starts the timer when a game in session, updating its display every second
     * until halted when the game ends.
     */
    private fun launchTimer() {
        isTimerRunning = true

        timerHandler.post(object : Runnable {
            override fun run() {
                if (isTimerRunning) {
                    tvTimeTaken.text = TimeUtil.displayTime(timeTaken++)
                    timerHandler.postDelayed(this, TimeUtil.SECONDS_TO_MILLISECONDS.toLong())
                } else {
                    timerHandler.removeCallbacks(this)
                }
            }
        })
    }

    /********************************
     * Methods Related to Shuffling *
     ********************************/

    /**
     * Handles the back-end and front-end operations when the puzzle tiles are shuffled.
     */
    private fun shuffle() {
        /* Display the progress bar, and update the message displayed */
        pbShuffle.visibility = View.VISIBLE
        pbShuffle.progress = 0
        btnShuffle.text = getString(R.string.randomizing)

        /* Display trivia in place of the upload button. */
        btnUpload.visibility = View.INVISIBLE
        tvTrivia.visibility = View.VISIBLE
        tvTrivia.text = getString(R.string.trivia)

        /*
         * Handle the case when the shuffle button is clicked while the success message
         * is still on display.
         */
        tvSuccess.visibility = View.GONE

        /* During shuffling, no UI element should be clickable. */
        disableClickables()

        /* Reset the displayed move number and time taken. */
        resetDisplayedStats()

        /* Generate the shuffled state. */
        getValidShuffledState()

        /* Apply dark filter to all the tiles before starting the animation. */
        displayBlankPuzzle()
        startShowingTiles()
    }

    /**
     * Generates a valid shuffling of the puzzle tiles.
     *
     * A shuffling is considered valid if the resulting state is not equivalent to the goal
     * state and if it is solvable (that is, it has an even number of inversions).
     */
    private fun getValidShuffledState() {
        val shuffledState: StatePair =
            ShuffleUtil.getValidShuffledState(puzzleState, goalPuzzleState, BLANK_TILE_MARKER)

        puzzleState = shuffledState.puzzleState
        blankTilePos = shuffledState.blankTilePos
    }

    /**
     * Updates the components depending on whether the shuffling animation is halfway finished
     * or fully completed.
     */
    private fun updateComponents() {
        when (pbShuffle.progress) {
            (NUM_TILES - 1) / 2 -> halfwayShuffling()
            (NUM_TILES - 1) -> finishShuffling()
        }
    }

    /**
     * Updates the components when the shuffling animation is halfway finished.
     */
    private fun halfwayShuffling() {
        btnShuffle.text = getString(R.string.inversions)
    }

    /**
     * Updates the components when the shuffling animation is fully completed.
     */
    private fun finishShuffling() {
        /* Signal the start of a new game. */
        isGameInSession = true

        /*
         * Change the colors of the game title and the buttons, as well as the text displayed,
         * to visually indicate that shuffling is finished.
         */
        tvTitle.setTextColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.btn_first_variant
            )
        )

        btnUpload.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.btn_second_variant
            )
        )

        btnShuffle.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.btn_first_variant
            )
        )

        btnShuffle.text = getString(R.string.randomized)

        /* Remove the progress bar and trivia, and re-enable interaction with UI elements. */
        pbShuffle.visibility = View.GONE
        tvTrivia.text = getString(R.string.trivia_a_star)
        enableClickables()
    }

    /**
     * Disables all the clickable components.
     */
    private fun disableClickables() {
        isPuzzleGridFrozen = true
        btnShuffle.isEnabled = false
        spnPuzzle.isEnabled = false
    }

    /**
     * Enables all the clickable components.
     */
    private fun enableClickables() {
        isPuzzleGridFrozen = false
        btnShuffle.isEnabled = true
    }

    /**
     * Removes the dark color filter on the specified tile as part of the shuffling animation.
     *
     * @param position Position of the tile whose filter is to be removed (zero-based, following
     * row-major order).
     */
    private fun showTileAt(position: Int) {
        tileImages[position].setImageBitmap(imageChunks[puzzleState[position]])

        /* Set (or reset) the adapter of the grid view. */
        gvgPuzzle.adapter = TileAdapter(tileImages, tileDimen, tileDimen)
    }

    /**
     * Removes the dark color filter on the eight non-blank tiles at random intervals
     * as part of the shuffling animation.
     */
    private fun startShowingTiles() {
        /* Concurrently show the tiles with randomized delay and order of appearance. */
        for (position in 0 until tileImages.size) {
            /* The blank tile should not be shown. */
            if (position != blankTilePos) {
                /* Randomize the delay. */
                val delay: Long =
                    ((0..AnimationUtil.SHUFFLING_ANIMATION_UPPER_BOUND).random()
                            + AnimationUtil.SHUFFLING_ANIMATION_OFFSET).toLong()

                /* Schedule the concurrent tasks of showing the tiles. */
                shuffleRunnable = ShuffleRunnable(shuffleHandler, position, NUM_TILES)
                shuffleScheduler.schedule(shuffleRunnable, delay, TimeUnit.MILLISECONDS)
            }
        }
    }

    /***************************************
     * Methods Related to Solution Display *
     ***************************************/

    /**
     * Handles the back-end and front-end operations when the user opts to display the solution
     * found by the app via the A* algorithm implemented in <code>SolveUtil</code>.
     */
    private fun solve() {
        puzzleSolution = SolveUtil.solve(
            StatePair(puzzleState, blankTilePos),
            goalPuzzleState,
            NUM_COLUMNS,
            BLANK_TILE_MARKER
        )

        /* Remove the displayed move number and time taken. */
        blankDisplayedStats()

        displaySolution()

        endGame(SolveStatus.COMPUTER_SOLVED)
    }

    /**
     * Displays the solution found by the app via the A* algorithm implemented in <code>SolveUtil</code>.
     */
    private fun displaySolution() {
        startSolution()

        puzzleSolution?.pop()!!
        numMovesSolution = puzzleSolution?.size!!

        animateSolution()
    }

    /**
     * Updates the display depending on whether the solution walkthrough is paused or resumed.
     *
     * This solution refers to the one found by the app via the A* algorithm implemented in
     * <code>SolveUtil</code>.
     */
    private fun controlSolutionDisplay() {
        if (isSolutionPlay) {
            pauseSolution()
        } else {
            resumeSolution()
        }
    }

    /**
     * Invoked when the solution walkthrough starts.
     *
     * This solution refers to the one found by the app via the A* algorithm implemented in
     * <code>SolveUtil</code>.
     */
    private fun startSolution() {
        isSolutionDisplay = true
        isSolutionPlay = true
        isPuzzleGridFrozen = true
    }

    /**
     * Handles the animation of the puzzle tiles during the solution walkthrough.
     *
     * This solution refers to the one found by the app via the A* algorithm implemented in
     * <code>SolveUtil</code>.
     */
    private fun animateSolution() {
        Handler(Looper.getMainLooper()).postDelayed({
            solveDisplayHandler.post(object : Runnable {
                override fun run() {
                    if (puzzleSolution?.isNotEmpty()!! && isSolutionDisplay && isSolutionPlay) {
                        if (!isSolutionSkip) {
                            solveDisplayHandler.postDelayed(
                                this,
                                AnimationUtil.MOVE_SOLUTION_DELAY.toLong()
                            )
                        } else {
                            solveDisplayHandler.postDelayed(this, 0)
                        }

                        val puzzleStatePair: StatePair = puzzleSolution?.pop()!!
                        puzzleState = puzzleStatePair.puzzleState
                        blankTilePos = puzzleStatePair.blankTilePos

                        displayPuzzle()

                        /*
                         * Check immediately so that the last move and the success message are displayed
                         * almost simultaneously.
                         */
                        if (puzzleSolution?.empty()!!) {
                            solveDisplayHandler.removeCallbacks(this)

                            endSolution()
                            displaySuccessMessage(SolveStatus.COMPUTER_SOLVED)
                            prepareForNewGame()
                        }
                    }
                }
            })
        }, AnimationUtil.FIRST_MOVE_SOLUTION_DELAY.toLong())
    }

    /**
     * Invoked when the solution walkthrough finishes.
     *
     * This solution refers to the one found by the app via the A* algorithm implemented in
     * <code>SolveUtil</code>.
     */
    private fun endSolution() {
        isSolutionDisplay = false
        isSolutionPlay = false
        isPuzzleGridFrozen = false
        isSolutionSkip = false
    }

    /**
     * Pauses the solution walkthrough.
     *
     * This solution refers to the one found by the app via the A* algorithm implemented in
     * <code>SolveUtil</code>.
     */
    private fun pauseSolution() {
        isSolutionPlay = false
        btnShuffle.text = getString(R.string.resume)
    }

    /**
     * Resumes playing the solution walkthrough.
     *
     * This solution refers to the one found by the app via the A* algorithm implemented in
     * <code>SolveUtil</code>.
     */
    private fun resumeSolution() {
        isSolutionPlay = true
        btnShuffle.text = getString(R.string.pause)

        animateSolution()
    }

    /**
     * Skips the solution walkthrough.
     *
     * This solution refers to the one found by the app via the A* algorithm implemented in
     * <code>SolveUtil</code>.
     */
    private fun skipSolution() {
        isSolutionSkip = true
        resumeSolution()
    }

    /*********************
     * Post-Game Methods *
     *********************/

    /**
     * Ends the game that is currently in session, in effect stopping the timer, displaying the
     * pertinent success message, and resetting the variables in preparation for a new game
     * if the user was able to solve the puzzle or for displaying the solution, otherwise.
     *
     * @param solveStatus Game status depending on whether the user solves the puzzle or opts
     * to play the solution walkthrough instead.
     */
    private fun endGame(solveStatus: SolveStatus) {
        /* Signal that the game is over. */
        isGameInSession = false
        isTimerRunning = false

        /* Save the updated statistics, and display them alongside the success message. */
        if (solveStatus != SolveStatus.COMPUTER_SOLVED) {
            saveStats(solveStatus)
            displaySuccessMessage(solveStatus)
            prepareForNewGame()
        } else {
            prepareForSolution()
        }
    }

    /**
     * Handles the back-end and front-end operations in preparation for a new game.
     */
    private fun prepareForNewGame() {
        /*
         * Revert the colors of the game title and the buttons, as well as the text displayed,
         * to visually indicate the start of a new game.
         */
        tvTitle.setTextColor(ContextCompat.getColor(applicationContext, R.color.btn_first))

        btnUpload.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.btn_second
            )
        )

        btnShuffle.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.btn_first
            )
        )

        btnShuffle.text = getString(R.string.new_game)

        /* Revert the visibility of the upload button (instead of the trivia). */
        btnUpload.visibility = View.VISIBLE
        btnUpload.text = getString(R.string.upload_picture)
        tvTrivia.visibility = View.GONE

        spnPuzzle.isEnabled = true
    }

    /**
     * Updates the components in preparation for showing the solution found by the app via the
     * A* algorithm implemented in <code>SolveUtil</code>.
     */
    private fun prepareForSolution() {
        btnShuffle.text = getString(R.string.pause)

        btnUpload.visibility = View.VISIBLE
        btnUpload.text = getString(R.string.skip)
        tvTrivia.visibility = View.GONE
    }

    /**
     * Updates the saved statistics (namely the fewest number of moves and fastest time taken to
     * solve the puzzle) at the end of a game provided that the user has set a new record for these
     * statistics.
     */
    private fun saveStats(solveStatus: SolveStatus) {
        when (solveStatus) {
            SolveStatus.FEWEST_MOVES -> saveFewestMoves()
            SolveStatus.FASTEST_TIME -> saveFastestTime()
            SolveStatus.FEWEST_AND_FASTEST -> saveFewestAndFastest()
            else -> Unit
        }
    }

    /**
     * Updates the saved fewest number of moves and fastest time taken to solve the puzzle
     * by storing the new values in the shared preferences file.
     */
    private fun saveFewestAndFastest() {
        fewestMoves = numMoves
        tvFewestMoves.text = fewestMoves.toString()

        fastestTime = timeTaken
        tvFastestTime.text = TimeUtil.displayTime(fastestTime)

        /*
         * Store in the shared preferences file.
         * These data are stored as strings to prevent problems related to integer overflow.
         */
        with(sp.edit()) {
            putString(Key.KEY_FEWEST_MOVES.name, fewestMoves.toString())
            putString(Key.KEY_FASTEST_TIME.name, fastestTime.toString())
            apply()
        }
    }

    /**
     * Updates the saved fewest number of moves taken to solve the puzzle by storing the new values
     * in the shared preferences file.
     */
    private fun saveFewestMoves() {
        fewestMoves = numMoves
        tvFewestMoves.text = fewestMoves.toString()

        /*
         * Store in the shared preferences file.
         * These data are stored as strings to prevent problems related to integer overflow.
         */
        with(sp.edit()) {
            putString(Key.KEY_FEWEST_MOVES.name, fewestMoves.toString())
            apply()
        }
    }

    /**
     * Updates the saved fastest time taken to solve the puzzle by storing the new values
     * in the shared preferences file.
     */
    private fun saveFastestTime() {
        fastestTime = timeTaken
        tvFastestTime.text = TimeUtil.displayTime(fastestTime)

        /*
         * Store in the shared preferences file.
         * These data are stored as strings to prevent problems related to integer overflow.
         */
        with(sp.edit()) {
            putString(Key.KEY_FASTEST_TIME.name, fastestTime.toString())
            apply()
        }
    }

    /**
     * Displays the pertinent success message based on the game status, which, in turn, depends
     * on whether the user solves the puzzle or opts to play the solution walkthrough instead.
     *
     * @param solveStatus Game status depending on whether the user solves the puzzle or opts
     * to play the solution walkthrough instead.
     */
    private fun displaySuccessMessage(solveStatus: SolveStatus) {
        /* Display a message depending on how the goal state of the puzzle was reached. */
        tvSuccess.visibility = View.VISIBLE
        tvSuccess.text = getString(solveStatus.successMessageId)

        if (solveStatus == SolveStatus.COMPUTER_SOLVED) {
            var message = "$numMovesSolution ${tvSuccess.text}"

            /* Display "1 Move" instead of "1 Moves". */
            if (numMovesSolution == 1) {
                message = message.substring(0, message.length - 1)
            }

            tvSuccess.text = message
        }

        /* Hide the success message after a set number of seconds. */
        Handler(Looper.getMainLooper()).postDelayed({
            tvSuccess.visibility = View.GONE
        }, AnimationUtil.SUCCESS_DISPLAY.toLong())
    }

    /***********************************************
     * Methods Related to Uploading a Puzzle Image *
     ***********************************************/

    /**
     * Obtains the necessary permission for choosing a photo from the Gallery as the puzzle image.
     */
    private fun uploadPuzzleImage() {
        UploadUtil.chooseFromGallery(this, galleryLauncher)
    }

    /**
     * Sets the puzzle image to the photo chosen from the Gallery and performs the necessary
     * bitmap manipulations to update the display on the puzzle grid.
     *
     * @param imagePath URI to the Gallery photo that will be set as the puzzle image.
     */
    private fun loadPuzzle(imagePath: Uri?) {
        isGalleryImageChosen = true
        resetState()

        /*
         * Handle the case when the spinner is clicked while the success message is still
         * on display.
         */
        tvSuccess.visibility = View.GONE

        updatePuzzleImage(imagePath)
        initChunks()
        displayPuzzle()
    }

    /**
     * Sets the puzzle image to the photo chosen from the Gallery
     *
     * @param imagePath URI to the Gallery photo that will be set as the puzzle image.
     */
    private fun updatePuzzleImage(imagePath: Uri?) {
        puzzleImage = ImageUtil.resizeToSquareBitmap(
            BitmapFactory.decodeStream(
                contentResolver.openInputStream(imagePath!!)
            ),
            puzzleDimen,
            puzzleDimen
        )
    }

    /**
     * Callback for the result from requesting permissions.
     *
     * @param requestCode The request code passed in <code>
     *     ActivityCompat.requestPermissions(android.app.Activity, String[], int)</code>.
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions which is either <code>
     *     PackageManager.PERMISSION_GRANTED</code> or <code>PackageManager.PERMISSION_DENIED</code>.
     *     Never null.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsResult(grantResults)
    }

    /**
     * Defines the behavior related to choosing an image from the Gallery based on the permissions
     * granted by the user.
     *
     * @param grantResults The grant results for the corresponding permissions which is either <code>
     *     PackageManager.PERMISSION_GRANTED</code> or <code>PackageManager.PERMISSION_DENIED</code>.
     *     Never null.
     */
    private fun permissionsResult(grantResults: IntArray) {
        UploadUtil.permissionsResultGallery(grantResults, this@MainActivity, galleryLauncher)
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