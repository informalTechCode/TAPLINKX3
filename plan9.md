Ah, wait. We need to update ALL the callers!
The build failed with "Destructuring of type 'kotlin.Unit' requires operator function 'component1()'." and "No value passed for parameter 'outPoint'."

Let's find all the places that call these functions and update them to pass the pre-allocated array.
