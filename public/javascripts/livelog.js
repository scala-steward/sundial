var delay = 3000
var params = {}
$(document).ready(function() {

    var baseTitle = document.title;

    var patterns = [
        {
            pattern: /(\d+-\d+-\d+ \d+:\d+:\d+,\d+) \[([^\]]+)\] (\S+)\s+([^ ]+) - (.*)/,
            groups: {
                timestamp: 1,
                thread: 2,
                level: 3,
                type: 4
            }
        }
    ];

    var newMessages = 0;
    var windowFocus = document.hasFocus();
    $(window).focus(function() {
        windowFocus = true;
        newMessages = 0;
        document.title = baseTitle;
    }).blur(function() {
        windowFocus = false;
    });

    var applyFilter = function(root) {
        var filter = $('#process-task').val();
        if(filter == "") {
            root.find('tr').show();
        } else {
            root.find('tr.task-' + filter).show();
            root.find('tr:not(.task-' + filter + ')').hide();
        }
    };

    $('#process-task').change(function() {
        window.location.hash = $('#process-task').val();
        applyFilter($('.sink'));
    });

    // If there's a hash, we use that as our initial filter
    var hash = window.location.hash.substr(1);
    if(hash) {
        var opt = $('<option>');
        opt.val(hash);
        opt.text(hash);
        $('#process-task').append(opt);
        $('#process-task').val(hash);
    }

    var showLogs = function () {
        setTimeout(showLogs, delay);
        $.post(endpoint, params, function (data) {
            params = data.params;

            var processInfo = data.process;
            $('#process-name').text(processInfo.name);
            $('#process-started').text(moment(processInfo.started).format('M/D H:mm:ss'));
            $('#process-duration').text(processInfo.duration);
            $('#process-status').text(processInfo.status);
            $('#last-updated').text(moment().format('H:mm:ss'));

            var color;
            if(processInfo.status == "Running") {
                delay = 3000;
                color = "#4985D6";
            } else if(processInfo.status == "Succeeded") {
                delay = 30000;
                color = "#A3FEBA";
            } else if(processInfo.status == "Failed") {
                delay = 30000;
                color = "#FFA8A8";
            }

            $('#process-info').css('background-color', color);

            var sink = $('<div>');
            var presentTasks = [];
            $.each(data.events, function (ix, event) {
                var row = $('<tr>');
                row.addClass('task-' + event[2])

                var nameTd = $('<td width="150" valign="top">');
                var timestampTd = $('<td width="120" valign="top">');
                var messageTd = $('<td valign="top">');
                var messagePre = $('<pre>');

                if($.inArray(event[2], presentTasks) < 0) {
                    presentTasks.push(event[2]);
                }

                nameTd.text(event[2]);
                timestampTd.text(moment(event[0]).format('M/D H:mm:ss'));

                var level;
                $.each(patterns, function(ix2, pattern) {
                    var match = pattern.pattern.exec(event[3]);
                    if(match != null) {
                        level = match[pattern.groups.level];
                    }
                });
                messagePre.html(event[3]);
                messagePre.css('white-space', 'pre-wrap');
                messagePre.css('margin', '0');

                if(level) {
                    row.addClass('level-' + level);
                }

                messageTd.append(messagePre);

                row.append(nameTd);
                row.append(timestampTd)
                row.append(messageTd);

                sink.append(row);
            });

            var taskSelect = $('#process-task');
            var currentTasks = [];
            $.each(taskSelect.find('option'), function() {
                var opt = $(this);
                currentTasks.push(opt.val());
            });
            presentTasks.sort();
            console.log(presentTasks);
            $.each(presentTasks, function(ix2, taskName) {
                if($.inArray(taskName, currentTasks) < 0) {
                    var opt = $('<option>');
                    opt.val(taskName);
                    opt.text(taskName);
                    taskSelect.append(opt);
                }
            });

            applyFilter(sink);
            if($('.sink').hasClass('not-loaded')) {
                $('.sink').empty();
                $('.sink').removeClass('not-loaded');
            }
            // move them all at once into the DOM
            $('.sink').append(sink.children());

            if(!windowFocus) {
                newMessages += data.events.length;
                if(newMessages > 0) {
                    document.title = '(' + newMessages + ') ' + baseTitle;
                }
            }

            if (data.events.length > 0) {
                if($('#chk-follow').is(':checked')) {
                    window.scrollTo(0, document.body.scrollHeight);
                }
            }

        });

    };

    showLogs();
});