console.log('KSAMSOK DEMO');

fetch('http://localhost:8080/Gradle___ksamsok_war/api?method=search&query=item=yxa AND itemNumber=734-54', {
    // headers: {Accept: 'application/json'}
})
    .then(response => response.text())
    .then(txt => console.log(txt))