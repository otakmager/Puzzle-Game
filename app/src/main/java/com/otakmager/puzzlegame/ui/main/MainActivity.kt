package com.otakmager.puzzlegame.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.ImageButton
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.otakmager.puzzlegame.R
import com.otakmager.puzzlegame.data.game.FlingDirection
import com.otakmager.puzzlegame.data.game.Key
import com.otakmager.puzzlegame.data.game.PuzzleImage
import com.otakmager.puzzlegame.data.state.SolveStatus
import com.otakmager.puzzlegame.data.state.StatePair
import com.otakmager.puzzlegame.databinding.ActivityMainBinding
import com.otakmager.puzzlegame.utils.event.OnFlingListener
import com.otakmager.puzzlegame.utils.game.*
import java.util.*
import java.util.Collections.swap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
            binding.spnPuzzle.setSelection(puzzleImageIndex)
        }
    }

    /**************************************
     * Methods Related to Initial Actions *
     **************************************/
    private fun initComponents() {
        setBtnShuffleAction()
        setBtnUploadAction()
        binding.tvSuccess.setOnClickListener {
            binding.tvSuccess.visibility = View.GONE
        }
        binding.spnPuzzle.adapter = SpinnerAdapter(
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

    private fun initHandlers() {
        /* Initialize thread pool executor and handler related to the shuffling animation. */
        shuffleScheduler = Executors.newScheduledThreadPool(NUM_TILES)
        shuffleHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                super.handleMessage(message)

                /* Animate UI elements while shuffling. */
                showTileAt(message.data.getInt(Key.KEY_TILE_POSITION.name))
                binding.pbShuffle.progress = message.data.getInt(Key.KEY_PROGRESS.name)
                updateComponents()
            }
        }

        /* Initialize the handler related to the timer. */
        timerHandler = Handler(Looper.getMainLooper())

        /* Initialize the handler related to the puzzle solution. */
        solveHandler = Handler(Looper.getMainLooper())
        solveDisplayHandler = Handler(Looper.getMainLooper())
    }

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

    private fun resetState() {
        puzzleState = goalPuzzleState.toMutableList() as ArrayList<Int>
        blankTilePos = BLANK_TILE_MARKER
    }

    private fun initPuzzle() {
        setTouchSlopThreshold()
        setOnFlingListener()
        setDimensions()
    }

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
    private fun setBtnShuffleAction() {
        binding.btnShuffle.setOnClickListener {
            if (isSolutionDisplay) {
                controlSolutionDisplay()
            } else if (!isGameInSession) {
                shuffle()
            } else {
                solve()
            }
        }
    }

    private fun setBtnUploadAction() {
        binding.btnUpload.setOnClickListener {
            if (isSolutionDisplay) {
                skipSolution()
            } else {
                if (binding.spnPuzzle.selectedItemPosition != indexOfCustom) {
                    binding.spnPuzzle.setSelection(indexOfCustom)
                } else {
                    uploadPuzzleImage()
                }
            }
        }
    }

    private fun setSpnPuzzleAction() {
        binding.spnPuzzle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
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
    private fun displayStats() {
        displayFewestMoves()
        displayFastestTime()
    }

    private fun displayFewestMoves() {
        binding.tvFewestMoves.text = if (fewestMoves == DEFAULT_FEWEST_MOVES) {
            getString(R.string.default_move_count)
        } else {
            fewestMoves.toString()
        }
    }

    private fun displayFastestTime() {
        binding.tvFastestTime.text = if (fastestTime == DEFAULT_FASTEST_TIME) {
            getString(R.string.default_timer)
        } else {
            TimeUtil.displayTime(fastestTime)
        }
    }

    private fun blankDisplayedStats() {
        /* Remove the statistics for the number of moves, and display them. */
        numMoves = 0
        binding.tvMoveNumber.text = getString(R.string.default_move_count)

        /* Remove the statistics for the time taken, and display them. */
        timeTaken = 0
        binding.tvTimeTaken.text = getString(R.string.default_timer)
    }

    private fun resetDisplayedStats() {
        /* Reset the statistics for the number of moves, and display them. */
        numMoves = 0
        binding.tvMoveNumber.text = numMoves.toString()

        /* Reset the statistics for the time taken, and display them. */
        timeTaken = 0
        binding.tvTimeTaken.text = TimeUtil.displayTime(timeTaken)
    }

    /**********************************
     * Methods Related to Puzzle Grid *
     **********************************/
    private fun setTouchSlopThreshold() {
        binding.gvgPuzzle.setTouchSlopThreshold(ViewConfiguration.get(this).scaledTouchSlop)
    }

    private fun setOnFlingListener() {
        binding.gvgPuzzle.setFlingListener(object : OnFlingListener {
            override fun onFling(direction: FlingDirection, position: Int) {
                moveTile(direction, position)
            }
        })
    }

    private fun setDimensions() {
        binding.gvgPuzzle.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.gvgPuzzle.viewTreeObserver.removeOnGlobalLayoutListener(this)

                /* Calculate the side length of each square tile. */
                puzzleDimen = binding.gvgPuzzle.measuredWidth
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
    private fun initPuzzleImage() {
        /* Retrieve the most recently displayed puzzle image. */
        puzzleImageIndex = sp.getInt(Key.KEY_PUZZLE_IMAGE.name, 0)
        binding.spnPuzzle.setSelection(puzzleImageIndex)

        puzzleImage = ImageUtil.resizeToSquareBitmap(
            ImageUtil.drawableToBitmap(
                this@MainActivity,
                puzzleImageChoices[puzzleImageIndex].drawableId
            ),
            puzzleDimen,
            puzzleDimen
        )
    }

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
        binding.gvgPuzzle.adapter = TileAdapter(tileImages, tileDimen, tileDimen)
    }

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
        binding.gvgPuzzle.adapter = TileAdapter(tileImages, tileDimen, tileDimen)
    }

    private fun loadPuzzle(position: Int) {
        /*
         * Handle the case when the spinner is clicked while the success message is still
         * on display.
         */
        binding.tvSuccess.visibility = View.GONE
        resetState()

        updatePuzzleImage(position)
        initChunks()
        displayPuzzle()
    }

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
                binding.tvSuccess.visibility = View.GONE
            }
        }
    }

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

    private fun trackMove() {
        numMoves++
        binding.tvMoveNumber.text = numMoves.toString()
    }

    private fun launchTimer() {
        isTimerRunning = true

        timerHandler.post(object : Runnable {
            override fun run() {
                if (isTimerRunning) {
                    binding.tvTimeTaken.text = TimeUtil.displayTime(timeTaken++)
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
    private fun shuffle() {
        /* Display the progress bar, and update the message displayed */
        binding.pbShuffle.visibility = View.VISIBLE
        binding.pbShuffle.progress = 0
        binding.btnShuffle.text = getString(R.string.randomizing)

        /* Display trivia in place of the upload button. */
        binding.btnUpload.visibility = View.INVISIBLE
        binding.tvTrivia.visibility = View.VISIBLE
        binding.tvTrivia.text = getString(R.string.trivia)

        /*
         * Handle the case when the shuffle button is clicked while the success message
         * is still on display.
         */
        binding.tvSuccess.visibility = View.GONE

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

    private fun getValidShuffledState() {
        val shuffledState: StatePair =
            ShuffleUtil.getValidShuffledState(puzzleState, goalPuzzleState, BLANK_TILE_MARKER)

        puzzleState = shuffledState.puzzleState
        blankTilePos = shuffledState.blankTilePos
    }

    private fun updateComponents() {
        when (binding.pbShuffle.progress) {
            (NUM_TILES - 1) / 2 -> halfwayShuffling()
            (NUM_TILES - 1) -> finishShuffling()
        }
    }

    private fun halfwayShuffling() {
        binding.btnShuffle.text = getString(R.string.inversions)
    }

    private fun finishShuffling() {
        /* Signal the start of a new game. */
        isGameInSession = true

        /*
         * Change the colors of the game title and the buttons, as well as the text displayed,
         * to visually indicate that shuffling is finished.
         */
        binding.tvTitle.setTextColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.btn_first_variant
            )
        )
        binding.btnUpload.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.btn_second_variant
            )
        )
        binding.btnShuffle.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.btn_first_variant
            )
        )
        binding.btnShuffle.text = getString(R.string.randomized)

        /* Remove the progress bar and trivia, and re-enable interaction with UI elements. */
        binding.pbShuffle.visibility = View.GONE
        binding.tvTrivia.text = getString(R.string.trivia_a_star)
        enableClickables()
    }

    private fun disableClickables() {
        isPuzzleGridFrozen = true
        binding.btnShuffle.isEnabled = false
        binding.spnPuzzle.isEnabled = false
    }

    private fun enableClickables() {
        isPuzzleGridFrozen = false
        binding.btnShuffle.isEnabled = true
    }

    private fun showTileAt(position: Int) {
        tileImages[position].setImageBitmap(imageChunks[puzzleState[position]])

        /* Set (or reset) the adapter of the grid view. */
        binding.gvgPuzzle.adapter = TileAdapter(tileImages, tileDimen, tileDimen)
    }

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

    /*********************************************************
     * Methods Related to Solution Display With A* Algorithm *
     *********************************************************/

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

    private fun displaySolution() {
        startSolution()

        puzzleSolution?.pop()!!
        numMovesSolution = puzzleSolution?.size!!

        animateSolution()
    }

    private fun controlSolutionDisplay() {
        if (isSolutionPlay) {
            pauseSolution()
        } else {
            resumeSolution()
        }
    }

    private fun startSolution() {
        isSolutionDisplay = true
        isSolutionPlay = true
        isPuzzleGridFrozen = true
    }

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

    private fun endSolution() {
        isSolutionDisplay = false
        isSolutionPlay = false
        isPuzzleGridFrozen = false
        isSolutionSkip = false
    }

    private fun pauseSolution() {
        isSolutionPlay = false
        binding.btnShuffle.text = getString(R.string.resume)
    }

    private fun resumeSolution() {
        isSolutionPlay = true
        binding.btnShuffle.text = getString(R.string.pause)

        animateSolution()
    }

    private fun skipSolution() {
        isSolutionSkip = true
        resumeSolution()
    }

    /*********************
     * Post-Game Methods *
     *********************/
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

    private fun prepareForNewGame() {
        // Change UI
        binding.tvTitle.setTextColor(ContextCompat.getColor(applicationContext, R.color.btn_first))
        binding.btnUpload.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.btn_second
            )
        )
        binding.btnShuffle.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.btn_first
            )
        )
        binding.btnShuffle.text = getString(R.string.new_game)

        /* Revert the visibility of the upload button (instead of the trivia). */
        binding.btnUpload.visibility = View.VISIBLE
        binding.btnUpload.text = getString(R.string.upload_picture)
        binding.tvTrivia.visibility = View.GONE
        binding.spnPuzzle.isEnabled = true
    }

    private fun prepareForSolution() {
        binding.btnShuffle.text = getString(R.string.pause)
        binding.btnUpload.visibility = View.VISIBLE
        binding.btnUpload.text = getString(R.string.skip)
        binding.tvTrivia.visibility = View.GONE
    }

    private fun saveStats(solveStatus: SolveStatus) {
        when (solveStatus) {
            SolveStatus.FEWEST_MOVES -> saveFewestMoves()
            SolveStatus.FASTEST_TIME -> saveFastestTime()
            SolveStatus.FEWEST_AND_FASTEST -> saveFewestAndFastest()
            else -> Unit
        }
    }

    private fun saveFewestAndFastest() {
        fewestMoves = numMoves
        binding.tvFewestMoves.text = fewestMoves.toString()

        fastestTime = timeTaken
        binding.tvFastestTime.text = TimeUtil.displayTime(fastestTime)

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

    private fun saveFewestMoves() {
        fewestMoves = numMoves
        binding.tvFewestMoves.text = fewestMoves.toString()

        /*
         * Store in the shared preferences file.
         * These data are stored as strings to prevent problems related to integer overflow.
         */
        with(sp.edit()) {
            putString(Key.KEY_FEWEST_MOVES.name, fewestMoves.toString())
            apply()
        }
    }

    private fun saveFastestTime() {
        fastestTime = timeTaken
        binding.tvFastestTime.text = TimeUtil.displayTime(fastestTime)

        /*
         * Store in the shared preferences file.
         * These data are stored as strings to prevent problems related to integer overflow.
         */
        with(sp.edit()) {
            putString(Key.KEY_FASTEST_TIME.name, fastestTime.toString())
            apply()
        }
    }

    private fun displaySuccessMessage(solveStatus: SolveStatus) {
        /* Display a message depending on how the goal state of the puzzle was reached. */
        binding.tvSuccess.visibility = View.VISIBLE
        binding.tvSuccess.text = getString(solveStatus.successMessageId)

        if (solveStatus == SolveStatus.COMPUTER_SOLVED) {
            var message = "$numMovesSolution ${binding.tvSuccess.text}"

            /* Display "1 Move" instead of "1 Moves". */
            if (numMovesSolution == 1) {
                message = message.substring(0, message.length - 1)
            }

            binding.tvSuccess.text = message
        }

        /* Hide the success message after a set number of seconds. */
        Handler(Looper.getMainLooper()).postDelayed({
            binding.tvSuccess.visibility = View.GONE
        }, AnimationUtil.SUCCESS_DISPLAY.toLong())
    }

    /***********************************************
     * Methods Related to Uploading a Puzzle Image *
     ***********************************************/
    private fun uploadPuzzleImage() {
        UploadUtil.chooseFromGallery(this, galleryLauncher)
    }

    private fun loadPuzzle(imagePath: Uri?) {
        isGalleryImageChosen = true
        resetState()

        /*
         * Handle the case when the spinner is clicked while the success message is still
         * on display.
         */
        binding.tvSuccess.visibility = View.GONE

        updatePuzzleImage(imagePath)
        initChunks()
        displayPuzzle()
    }

    private fun updatePuzzleImage(imagePath: Uri?) {
        puzzleImage = ImageUtil.resizeToSquareBitmap(
            BitmapFactory.decodeStream(
                contentResolver.openInputStream(imagePath!!)
            ),
            puzzleDimen,
            puzzleDimen
        )
    }

    /**************************
     * Requesting permissions *
     **************************/
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsResult(grantResults)
    }

    private fun permissionsResult(grantResults: IntArray) {
        UploadUtil.permissionsResultGallery(grantResults, this@MainActivity, galleryLauncher)
    }

    companion object {
        private const val NUM_COLUMNS = 3
        private const val NUM_TILES = NUM_COLUMNS * NUM_COLUMNS
        private const val BORDER_OFFSET = 6
        private const val BLANK_TILE_MARKER = NUM_TILES - 1
        private const val DEFAULT_FEWEST_MOVES = Long.MAX_VALUE
        private const val DEFAULT_FASTEST_TIME = Long.MAX_VALUE
    }
}