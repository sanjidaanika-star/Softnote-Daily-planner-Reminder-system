const image = document.getElementById("home-study-photo");
const photo = image.closest(".home-photo");
function hideUnavailablePhoto() { photo.hidden = true; }
image.addEventListener("error", hideUnavailablePhoto);
if (image.complete && image.naturalWidth === 0) hideUnavailablePhoto();
